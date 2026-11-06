package dev.ryanbuu.infrapilot.forwarding;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import dev.ryanbuu.infrapilot.api.ApiException;
import java.net.*;
import java.util.*;
import org.xbill.DNS.Address;

public final class ForwardingRules {
  private ForwardingRules() {}

  public record Rule(
      String domain,
      String transport,
      String forward,
      int port,
      List<String> addresses,
      String tlsServerName,
      String description) {}

  public static String domain(String value) {
    try {
      String name =
          IDN.toASCII(
                  Objects.requireNonNull(value).trim().replaceFirst("\\.$", ""),
                  IDN.USE_STD3_ASCII_RULES)
              .toLowerCase(Locale.ROOT);
      if (name.isEmpty()
          || name.length() > 253
          || Arrays.stream(name.split("\\.", -1))
              .anyMatch(s -> !s.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")))
        throw new IllegalArgumentException();
      return name;
    } catch (Exception e) {
      throw bad("请输入有效域名，不支持通配符或根区域");
    }
  }

  public static List<Rule> validate(List<Rule> rules) {
    if (rules == null || rules.size() > 256) throw bad("规则数量无效，最多 256 条");
    Set<String> seen = new HashSet<>();
    List<Rule> result = new ArrayList<>();
    for (Rule r : rules) {
      if (r == null) throw bad("规则格式无效");
      String name = domain(r.domain());
      if (!name.contains(".") || !seen.add(name)) throw bad("域名重复或不是完整域名");
      if (!Set.of("DNS", "DOT").contains(Objects.toString(r.transport(), ""))
          || !Set.of("only", "first").contains(Objects.toString(r.forward(), "")))
        throw bad("转发协议或模式无效");
      if (r.port() < 1 || r.port() > 65535) throw bad("端口须为 1–65535");
      if (r.addresses() == null || r.addresses().isEmpty() || r.addresses().size() > 8)
        throw bad("请提供 1–8 个上游 IP");
      TreeSet<String> addresses = new TreeSet<>();
      for (String ip : r.addresses()) {
        try {
          // dnsjava's literal-only parser never resolves a supplied hostname.
          InetAddress address = Address.getByAddress(ip);
          if (address.isAnyLocalAddress() || address.isMulticastAddress()) throw new Exception();
          addresses.add(address.getHostAddress());
        } catch (Exception e) {
          throw bad("上游必须是有效的 IPv4 或 IPv6 单播地址");
        }
      }
      String description = Objects.toString(r.description(), "").trim();
      if (description.length() > 200) throw bad("备注最多 200 个字符");
      result.add(
          new Rule(
              name,
              r.transport(),
              r.forward(),
              r.port(),
              List.copyOf(addresses),
              r.transport().equals("DOT") ? domain(r.tlsServerName()) : "",
              description));
    }
    result.sort(Comparator.comparing(Rule::domain));
    return List.copyOf(result);
  }

  public static void protect(List<Rule> rules, Collection<String> zones) {
    for (Rule r : rules)
      for (String value : zones) {
        String p = value.replaceFirst("\\.$", "").toLowerCase(Locale.ROOT);
        if (!p.equals(".")
            && (r.domain().equals(p)
                || r.domain().endsWith("." + p)
                || p.endsWith("." + r.domain()))) throw bad("转发域名与本地权威区域重叠，已拒绝覆盖");
      }
  }

  private static ApiException bad(String text) {
    return new ApiException(BAD_REQUEST, text);
  }
}
