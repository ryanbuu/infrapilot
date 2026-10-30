package dev.ryanbuu.infrapilot.gateway;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ryanbuu.infrapilot.api.ApiException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GatewayBackups {
  private final GatewayProperties p;
  private final ObjectMapper mapper;

  public GatewayBackups(GatewayProperties p, ObjectMapper mapper) {
    this.p = p;
    this.mapper = mapper;
  }

  private Path root() throws Exception {
    Path root = Path.of(p.getBackupDirectory()).toAbsolutePath().normalize();
    Files.createDirectories(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    return root;
  }

  public String save(ObjectNode snapshot) {
    try {
      String id = UUID.randomUUID().toString();
      ObjectNode data =
          snapshot.deepCopy().put("id", id).put("createdAt", Instant.now().toString());
      Path directory = root();
      Path file = directory.resolve(id + ".json"), temp = directory.resolve(id + ".tmp");
      Files.createFile(
          temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer bytes =
            ByteBuffer.wrap(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
      return id;
    } catch (Exception e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法写入网关回滚备份，本次变更未执行");
    }
  }

  public ObjectNode read(String id) {
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
      Path file = root().resolve(id + ".json");
      if (Files.isSymbolicLink(file) || Files.size(file) > 16_000_000)
        throw new IllegalArgumentException();
      return (ObjectNode) mapper.readTree(Files.readString(file));
    } catch (Exception e) {
      throw new ApiException(HttpStatus.NOT_FOUND, "回滚备份不存在或无法读取");
    }
  }

  public List<JsonNode> list() {
    try (var files = Files.list(root())) {
      return files
          .filter(f -> f.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
          .sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
          .limit(200)
          .map(f -> read(f.getFileName().toString().replace(".json", "")))
          .map(
              n -> {
                ObjectNode meta = mapper.createObjectNode();
                for (String key :
                    List.of(
                        "id",
                        "createdAt",
                        "kind",
                        "resourceId",
                        "parent",
                        "action",
                        "actor",
                        "revision")) if (n.has(key)) meta.set(key, n.get(key));
                return (JsonNode) meta;
              })
          .toList();
    } catch (Exception e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法读取网关备份目录");
    }
  }
}
