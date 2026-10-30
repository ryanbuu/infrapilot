package dev.ryanbuu.infrapilot.dns;

import static org.springframework.http.HttpStatus.*;

import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.audit.AuditService;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.Zone;
import dev.ryanbuu.infrapilot.dns.DnsModels.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

@Service
public class DnsService {
  private final ZoneRegistry registry;
  private final DnsGateway gateway;
  private final RecordPolicy policy;
  private final AuditService audit;

  public DnsService(
      ZoneRegistry registry, DnsGateway gateway, RecordPolicy policy, AuditService audit) {
    this.registry = registry;
    this.gateway = gateway;
    this.policy = policy;
    this.audit = audit;
  }

  public List<ZoneView> zones() {
    return registry.all().stream()
        .map(
            z -> {
              try {
                return view(z, gateway.read(z), null);
              } catch (ApiException e) {
                return view(z, null, e.getMessage());
              }
            })
        .toList();
  }

  public ZoneRecords records(String id) {
    Zone z = registry.find(id);
    List<Record> records = gateway.read(z);
    return new ZoneRecords(view(z, records, null), rrsets(z, records));
  }

  private ZoneView view(Zone z, List<Record> records, String error) {
    return new ZoneView(
        z.id(),
        z.name(),
        z.description(),
        !z.writable(),
        z.primary().host(),
        z.replicas().stream().map(e -> e.host()).toList(),
        records == null ? null : serial(records),
        records == null ? null : rrsets(z, records).size(),
        error);
  }

  private long serial(List<Record> records) {
    return records.stream()
        .filter(SOARecord.class::isInstance)
        .map(SOARecord.class::cast)
        .mapToLong(SOARecord::getSerial)
        .findFirst()
        .orElse(0);
  }

  public List<Rrset> rrsets(Zone z, List<Record> snapshot) {
    Map<String, List<Record>> groups = new TreeMap<>();
    for (Record r : snapshot)
      groups
          .computeIfAbsent(
              r.getName().canonicalize() + "|" + Type.string(r.getType()), k -> new ArrayList<>())
          .add(r);
    return groups.values().stream()
        .map(g -> dto(z, g.stream().distinct().toList(), snapshot))
        .toList();
  }

  private Rrset dto(Zone z, List<Record> records, List<Record> snapshot) {
    if (records.isEmpty()) return null;
    Record r = records.getFirst();
    String protection = policy.protection(z, r.getName(), r.getType(), snapshot);
    return new Rrset(
        r.getName().toString(),
        Type.string(r.getType()),
        r.getTTL(),
        records.stream().map(Record::rdataToString).distinct().sorted().toList(),
        policy.revision(records),
        protection == null,
        protection);
  }

  public long create(String id, Change change, String actor) {
    return mutate(id, change, null, false, true, actor);
  }

  public long update(String id, Change change, String actor) {
    return mutate(id, change, null, false, false, actor);
  }

  public long delete(String id, Mutation mutation, String actor) {
    if (mutation.name() == null || mutation.type() == null)
      throw new ApiException(BAD_REQUEST, "请指定记录名称和类型");
    return mutate(id, null, mutation, true, false, actor);
  }

  private long mutate(
      String id, Change change, Mutation mutation, boolean delete, boolean create, String actor) {
    Zone z = registry.find(id);
    if (!z.writable()) throw new ApiException(FORBIDDEN, "此区域采用动态注册管理，不支持静态配置变更");
    List<Record> snapshot = gateway.read(z);
    Name name = policy.name(delete ? mutation.name() : change.name(), z);
    String type = (delete ? mutation.type() : change.type()).toUpperCase(Locale.ROOT);
    String reason = policy.protection(z, name, Type.value(type), snapshot);
    if (reason != null) throw new ApiException(FORBIDDEN, reason);
    List<Record> before =
        snapshot.stream()
            .filter(r -> r.getName().equals(name) && Type.string(r.getType()).equals(type))
            .toList();
    String expected = delete ? mutation.expectedRevision() : change.expectedRevision();
    if (create && !before.isEmpty()) throw new ApiException(CONFLICT, "此记录集已存在，请使用编辑功能");
    if (!create
        && (before.isEmpty() || expected == null || !policy.revision(before).equals(expected)))
      throw new ApiException(CONFLICT, "记录已变化，请刷新后重试");
    List<Record> after = delete ? List.of() : policy.parse(change, z);
    if (!delete) {
      boolean cnameConflict =
          snapshot.stream()
              .anyMatch(
                  r ->
                      r.getName().equals(name)
                          && r.getType() != Type.RRSIG
                          && r.getType() != Type.NSEC
                          && r.getType() != Type.value(type)
                          && (type.equals("CNAME") || r.getType() == Type.CNAME));
      if (cnameConflict) throw new ApiException(CONFLICT, "CNAME 不能与同名的其他记录共存");
    }
    long event =
        audit.begin(
            actor,
            id,
            delete ? "DELETE" : create ? "CREATE" : "UPDATE",
            name.toString(),
            type,
            dto(z, before, snapshot),
            dto(z, after, snapshot));
    try {
      gateway.change(z, snapshot, before, after);
    } catch (RuntimeException e) {
      audit.finish(
          event,
          e instanceof UncertainDnsException ? "PENDING" : "FAILED",
          e instanceof ApiException ? e.getMessage() : "DNS 更新未完成");
      throw e;
    }
    try {
      audit.finish(event, "SUCCESS", "DNS 更新成功");
    } catch (RuntimeException e) {
      throw new ApiException(SERVICE_UNAVAILABLE, "DNS 已更新，但审计状态未完成；请刷新记录并核查操作 #" + event);
    }
    return event;
  }

  public List<ProbeResult> probe(String id, String name, String type) {
    Zone z = registry.find(id);
    Name n = policy.name(name, z);
    String t = type.toUpperCase(Locale.ROOT);
    if (!RecordPolicy.WRITABLE_TYPES.contains(t)
        && !Set.of(
                "NS",
                "SOA",
                "DNSKEY",
                "DS",
                "RRSIG",
                "DHCID",
                "NSEC",
                "NSEC3",
                "NSEC3PARAM",
                "CDS",
                "CDNSKEY")
            .contains(t)) throw new ApiException(BAD_REQUEST, "不支持此查询类型");
    List<ProbeResult> result = new ArrayList<>();
    result.add(gateway.probe(z, z.primary(), n.toString(), t, true));
    for (var endpoint : z.replicas())
      result.add(gateway.probe(z, endpoint, n.toString(), t, false));
    return result;
  }
}
