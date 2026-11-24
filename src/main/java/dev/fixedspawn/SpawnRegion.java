package dev.fixedspawn;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

record SpawnRegion(String worldName, int minX, int maxX, int minZ, int maxZ,
                   int attempts, int minDistance) {
    private static final int LIMIT = 29_999_983;
    private static final int DEFAULT_MIN_DISTANCE = 32;
    private static final Set<Material> HAZARDS = Set.of(
            Material.LAVA, Material.WATER, Material.POWDER_SNOW, Material.FIRE,
            Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.WITHER_ROSE,
            Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE);

    static SpawnRegion read(YamlConfiguration config) {
        if (!config.isString("world")) {
            throw new IllegalArgumentException("config.yml의 world는 문자열이어야 합니다.");
        }
        int minX = number(config, "min-x", -LIMIT, LIMIT);
        int maxX = number(config, "max-x", -LIMIT, LIMIT);
        int minZ = number(config, "min-z", -LIMIT, LIMIT);
        int maxZ = number(config, "max-z", -LIMIT, LIMIT);
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("각 축의 최소 좌표는 최대 좌표 이하여야 합니다.");
        }
        return new SpawnRegion(config.getString("world").trim(), minX, maxX, minZ, maxZ,
                number(config, "attempts", 1, 256),
                config.contains("min-distance") ? number(config, "min-distance", 0, Integer.MAX_VALUE)
                        : DEFAULT_MIN_DISTANCE);
    }

    private static int number(YamlConfiguration config, String key, int min, int max) {
        if (!config.isInt(key) || config.getInt(key) < min || config.getInt(key) > max) {
            throw new IllegalArgumentException(key + " 값은 " + min + " ~ " + max + " 범위의 정수여야 합니다.");
        }
        return config.getInt(key);
    }

    World world() {
        World world = worldName.isEmpty() ? Bukkit.getServer().getRespawnWorld() : Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalArgumentException("스폰 월드가 로드되지 않았습니다: " + worldName);
        }
        return world;
    }

    SpawnStore.Point find(UUID player, Map<UUID, SpawnStore.Point> assigned) {
        World world = world();
        UUID worldId = world.getUID();
        SpawnStore.Point previous = assigned.get(player);
        double minDistanceSquared = (double) minDistance * minDistance;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = random.nextInt(minX, maxX + 1);
            int z = random.nextInt(minZ, maxZ + 1);
            Location candidate = new Location(world, x + 0.5, world.getMinHeight(), z + 0.5);
            if (!world.getWorldBorder().isInside(candidate) || sameColumn(previous, worldId, x, z)
                    || tooClose(player, assigned, worldId, candidate, minDistanceSquared)) {
                continue;
            }
            // The height map can give an empty result on an unloaded chunk.
            world.getChunkAt(candidate);
            int groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (groundY < world.getMinHeight() || groundY + 2 >= world.getMaxHeight()) {
                continue;
            }
            Block ground = world.getBlockAt(x, groundY, z);
            if (!ground.getType().isSolid() || !dry(ground) || !ground.getCollisionShape().overlaps(
                    new BoundingBox(0.49, 0.99, 0.49, 0.51, 1, 0.51))
                    || HAZARDS.contains(ground.getType())
                    || !clear(world.getBlockAt(x, groundY + 1, z))
                    || !clear(world.getBlockAt(x, groundY + 2, z))) {
                continue;
            }
            candidate.setY(groundY + 1);
            return new SpawnStore.Point(candidate);
        }
        return null;
    }

    private static boolean sameColumn(SpawnStore.Point previous, UUID world, int x, int z) {
        return previous != null && previous.world().equals(world)
                && Location.locToBlock(previous.x()) == x && Location.locToBlock(previous.z()) == z;
    }

    private static boolean tooClose(UUID player, Map<UUID, SpawnStore.Point> assigned, UUID world,
                                    Location candidate, double minDistanceSquared) {
        if (minDistanceSquared == 0) {
            return false;
        }
        for (var entry : assigned.entrySet()) {
            SpawnStore.Point point = entry.getValue();
            if (player.equals(entry.getKey()) || !world.equals(point.world())) {
                continue;
            }
            double deltaX = candidate.getX() - point.x();
            double deltaZ = candidate.getZ() - point.z();
            if (deltaX * deltaX + deltaZ * deltaZ < minDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    private static boolean clear(Block block) {
        return block.isPassable() && dry(block) && !HAZARDS.contains(block.getType());
    }

    private static boolean dry(Block block) {
        return !block.isLiquid() && !(block.getBlockData() instanceof Waterlogged data && data.isWaterlogged());
    }
}
