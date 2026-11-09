package dev.ryanbuu.infrapilot.dns;

import static org.springframework.http.HttpStatus.*;

import dev.ryanbuu.infrapilot.api.ApiException;
import dev.ryanbuu.infrapilot.config.InfraPilotProperties.Zone;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

@Component
public class RecordPolicy {
  public static final Set<String> WRITABLE_TYPES =
      Set.of(
          "A",
          "AAAA",
          "CNAME",
          "MX",
          "TXT",
          "PTR",
          "SRV",
          "CAA",
          "TLSA",
          "SSHFP",
          "NAPTR",
          "URI",
          "SVCB",
          "HTTPS",
          "DNAME",
          "LOC",
          "HINFO",
          "RP",
          "CERT",
          "IPSECKEY",
          "OPENPGPKEY",
          "CSYNC",
          "ZONEMD",
          "EUI48",
          "EUI64");

  public Name name(String value, Zone z) {
    try {
      Name zone = Name.fromString(z.name());
      Name n = Name.fromString(value, zone).canonicalize();
      if (!n.subdomain(zone)) throw new ApiException(BAD_REQUEST, "记录必须属于当前区域");
      return n;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(BAD_REQUEST, "DNS 名称格式无效");
    }
  }

  public String protection(Zone z, Name n, int type, List<Record> records) {
    if (!z.writable()) return "此区域采用动态注册管理，仅供查询与核验";
    if (!WRITABLE_TYPES.contains(Type.string(type))) return "此记录由 DNS 系统管理";
    if (type == Type.CNAME && n.toString().equalsIgnoreCase(z.name())) return "区域根记录不能设置为 CNAME";
    for (String suffix : z.protectedNames())
      try {
        if (n.subdomain(Name.fromString(suffix))) return "此子域由其他服务维护";
      } catch (Exception e) {
        throw new IllegalStateException("Invalid protected suffix");
      }
    if (records.stream().anyMatch(r -> r.getName().equals(n) && r.getType() == Type.DHCID))
      return "此名称受 DHCID 所有权保护，通过动态注册维护";
    for (Record r : records)
      if (r.getType() == Type.NS
          && !r.getName().toString().equalsIgnoreCase(z.name())
          && n.subdomain(r.getName())) return "此名称位于已委派的子域中";
    return null;
  }

  public List<Record> parse(DnsModels.Change change, Zone z) {
    Name n = name(change.name(), z);
    String type = change.type().toUpperCase(Locale.ROOT);
    if (!WRITABLE_TYPES.contains(type)) throw new ApiException(FORBIDDEN, "不允许修改此记录类型");
    List<Record> values = new ArrayList<>();
    try {
      for (String v : change.values()) {
        Record r =
            Record.fromString(
                n, Type.value(type), DClass.IN, change.ttl(), v, Name.fromString(z.name()));
        if (r == null) throw new Exception();
        values.add(r);
      }
    } catch (Exception e) {
      throw new ApiException(BAD_REQUEST, "记录值格式无效，请检查 IP、域名或文本格式");
    }
    if (type.equals("CNAME") && values.size() != 1)
      throw new ApiException(BAD_REQUEST, "CNAME 只能有一个目标");
    return values.stream().distinct().toList();
  }

  public String revision(List<Record> records) {
    try {
      String value =
          records.stream()
              .map(
                  r ->
                      r.getName().canonicalize()
                          + "|"
                          + r.getType()
                          + "|"
                          + r.getTTL()
                          + "|"
                          + HexFormat.of().formatHex(r.rdataToWireCanonical()))
              .sorted()
              .reduce("", (a, b) -> a + "\n" + b);
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
