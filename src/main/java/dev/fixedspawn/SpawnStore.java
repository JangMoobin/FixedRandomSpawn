package dev.fixedspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class SpawnStore {
    private final Path file;
    private boolean active;
    private Map<UUID, Point> points = Map.of();

    SpawnStore(Path file) throws IOException, InvalidConfigurationException {
        this.file = file;
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        active = yaml.getBoolean("active");
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            if (yaml.contains("players")) {
                throw new IOException("state.yml: 잘못된 players 항목");
            }
            return;
        }
        Map<UUID, Point> loaded = new HashMap<>();
        for (String key : section.getKeys(false)) {
            loaded.put(UUID.fromString(key), new Point(
                    UUID.fromString(section.getString(key + ".world", "")),
                    section.getDouble(key + ".x", Double.NaN),
                    section.getDouble(key + ".y", Double.NaN),
                    section.getDouble(key + ".z", Double.NaN)));
        }
        points = Map.copyOf(loaded);
    }

    boolean active() {
        return active;
    }

    Map<UUID, Point> points() {
        return points;
    }

    void save(boolean enabled, Map<UUID, Point> updated) throws IOException {
        Map<UUID, Point> snapshot = Map.copyOf(updated);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", enabled);
        snapshot.forEach((id, point) -> {
            String path = "players." + id + ".";
            yaml.set(path + "world", point.world().toString());
            yaml.set(path + "x", point.x());
            yaml.set(path + "y", point.y());
            yaml.set(path + "z", point.z());
        });
        Path pending = Files.createTempFile(file.getParent(), "state-", ".tmp");
        try {
            yaml.save(pending.toFile());
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
        active = enabled;
        points = snapshot;
    }

    record Point(UUID world, double x, double y, double z) {
        Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("스폰 좌표는 유한한 숫자여야 합니다.");
            }
        }

        Point(Location location) {
            this(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
        }

        Location resolve() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z);
        }
    }
}
