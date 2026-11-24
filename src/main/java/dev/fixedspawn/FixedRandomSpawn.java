package dev.fixedspawn;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class FixedRandomSpawn extends JavaPlugin implements Listener {
    private SpawnStore store;
    private SpawnRegion region;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            store = new SpawnStore(getDataFolder().toPath().resolve("state.yml"));
            region = readRegion();
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "스폰 데이터를 읽지 못했습니다.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private SpawnRegion readRegion() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(getDataFolder().toPath().resolve("config.yml").toFile());
        return SpawnRegion.read(config);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID id = event.getConnection().getProfile().getId();
        if (id == null) {
            return;
        }
        boolean firstJoin = event.isNewPlayer();
        // World, configuration, and spawn data are accessed only on the server thread.
        Future<Location> selection = Bukkit.getScheduler().callSyncMethod(this, () -> {
            if (!store.active()) {
                return null;
            }
            SpawnStore.Point point = getOrAssign(id);
            return firstJoin && point != null ? point.resolve() : null;
        });
        try {
            Location spawn = selection.get(30, TimeUnit.SECONDS);
            if (spawn != null) {
                event.setSpawnLocation(spawn);
            }
        } catch (InterruptedException e) {
            selection.cancel(false);
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            selection.cancel(false);
            getLogger().warning("입장 스폰 선택 시간 초과: " + id);
        } catch (ExecutionException e) {
            getLogger().log(Level.WARNING, "입장 스폰 선택 실패: " + id, e.getCause());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!store.active() || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH
                || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        try {
            SpawnStore.Point point = getOrAssign(event.getPlayer().getUniqueId());
            Location spawn = point == null ? null : point.resolve();
            if (spawn != null) {
                event.setRespawnLocation(spawn);
            }
        } catch (IOException | IllegalArgumentException e) {
            getLogger().log(Level.WARNING, "리스폰 좌표를 적용하지 못했습니다.", e);
        }
    }

    private SpawnStore.Point getOrAssign(UUID id) throws IOException {
        SpawnStore.Point point = store.points().get(id);
        if (point != null) {
            return point;
        }
        point = region.find(id, store.points());
        if (point == null) {
            getLogger().warning("설정 범위에서 안전 조건과 최소 간격을 충족하는 스폰을 찾지 못했습니다: " + id);
        } else {
            savePoint(id, point);
        }
        return point;
    }

    private void savePoint(UUID id, SpawnStore.Point point) throws IOException {
        Map<UUID, SpawnStore.Point> updated = new HashMap<>(store.points());
        updated.put(id, point);
        store.save(store.active(), updated);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fixedspawn.admin")) {
            sender.sendMessage("이 명령어를 사용할 권한이 없습니다.");
            return true;
        }
        if (args.length == 0) {
            return false;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    if (args.length != 1) return false;
                    start(sender);
                }
                case "stop" -> {
                    if (args.length != 1) return false;
                    store.save(false, store.points());
                    sender.sendMessage("고정 스폰 적용을 중지했습니다. 배정 좌표는 저장되어 있습니다.");
                }
                case "status" -> {
                    if (args.length == 1) {
                        sender.sendMessage("고정 스폰: " + (store.active() ? "활성" : "중지")
                                + " / 배정 인원: " + store.points().size());
                    } else if (args.length == 2) {
                        SpawnStore.Point point = store.points().get(playerId(args[1]));
                        sender.sendMessage(point == null ? "배정된 스폰이 없습니다." : describe(args[1], point));
                    } else {
                        return false;
                    }
                }
                case "reload" -> {
                    if (args.length != 1) return false;
                    SpawnRegion reloaded = readRegion();
                    reloaded.world();
                    region = reloaded;
                    sender.sendMessage("랜덤 스폰 설정을 다시 읽었습니다. 최소 간격: " + region.minDistance() + "블록");
                }
                case "reroll" -> {
                    if (args.length != 2) {
                        sender.sendMessage("사용법: /" + label + " reroll <플레이어 또는 UUID>");
                        return true;
                    }
                    UUID id = playerId(args[1]);
                    SpawnStore.Point point = region.find(id, store.points());
                    if (point == null) {
                        sender.sendMessage("범위 안에서 안전 조건과 최소 간격을 충족하는 새 스폰을 찾지 못했습니다.");
                        return true;
                    }
                    savePoint(id, point);
                    sender.sendMessage(describe(args[1], point));
                }
                case "set" -> {
                    if (args.length != 5 && args.length != 6) {
                        sender.sendMessage("사용법: /" + label + " set <플레이어 또는 UUID> <x> <y> <z> [월드]");
                        return true;
                    }
                    UUID id = playerId(args[1]);
                    World world = args.length == 6 ? Bukkit.getWorld(args[5]) : region.world();
                    if (world == null) {
                        throw new IllegalArgumentException("스폰 월드가 로드되지 않았습니다: " + args[5]);
                    }
                    SpawnStore.Point point = manualPoint(world, args[2], args[3], args[4]);
                    savePoint(id, point);
                    sender.sendMessage(describe(args[1], point));
                }
                default -> {
                    return false;
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            sender.sendMessage("설정 또는 스폰 데이터를 처리하지 못했습니다. 서버 로그를 확인하세요.");
            getLogger().log(Level.WARNING, "고정 스폰 명령 처리 실패", e);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(e instanceof NumberFormatException ? "좌표는 숫자로 입력하세요." : e.getMessage());
        }
        return true;
    }

    private void start(CommandSender sender) throws IOException {
        region.world();
        Map<UUID, SpawnStore.Point> updated = new HashMap<>(store.points());
        int assigned = 0;
        int failed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (updated.containsKey(id)) {
                continue;
            }
            SpawnStore.Point point = region.find(id, updated);
            if (point == null) {
                failed++;
                getLogger().warning("설정 범위에서 안전 조건과 최소 간격을 충족하는 스폰을 찾지 못했습니다: " + player.getName());
            } else {
                updated.put(id, point);
                assigned++;
            }
        }
        store.save(true, updated);
        sender.sendMessage("고정 스폰을 활성화했습니다. 신규 배정: " + assigned + "명 / 배정 실패: " + failed + "명");
    }

    private UUID playerId(String value) {
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(value);
            if (cached != null && cached.hasPlayedBefore()) {
                return cached.getUniqueId();
            }
            throw new IllegalArgumentException("플레이어를 찾을 수 없습니다. 정확한 이름 또는 UUID를 입력하세요.");
        }
    }

    private SpawnStore.Point manualPoint(World world, String x, String y, String z) {
        SpawnStore.Point point = new SpawnStore.Point(world.getUID(),
                Double.parseDouble(x), Double.parseDouble(y), Double.parseDouble(z));
        if (point.y() < world.getMinHeight() || point.y() >= world.getMaxHeight() - 1
                || !world.getWorldBorder().isInside(new Location(world, point.x(), point.y(), point.z()))) {
            throw new IllegalArgumentException("월드 경계와 높이 제한 안의 좌표를 입력하세요.");
        }
        return point;
    }

    private String describe(String player, SpawnStore.Point point) {
        World world = Bukkit.getWorld(point.world());
        String name = world == null ? point.world().toString() : world.getName();
        return player + "의 기본 스폰: " + name + " (" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fixedspawn.admin")) {
            return List.of();
        }
        List<String> options = switch (args.length) {
            case 1 -> List.of("start", "stop", "status", "reload", "reroll", "set");
            case 2 -> List.of("status", "reroll", "set").contains(args[0].toLowerCase(Locale.ROOT))
                    ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
            case 6 -> args[0].equalsIgnoreCase("set")
                    ? Bukkit.getWorlds().stream().map(World::getName).toList() : List.of();
            default -> List.of();
        };
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
