package io.akarin.forge.server;

import java.io.File;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.World.Environment;
import org.bukkit.craftbukkit.v1_12_R1.Main;
import org.bukkit.craftbukkit.v1_12_R1.scoreboard.CraftScoreboardManager;
import org.bukkit.craftbukkit.v1_12_R1.util.Waitable;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;

import joptsimple.OptionSet;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTimeUpdate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.PendingCommand;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.util.ReportedException;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

public abstract class AkarinHooks {
	private static final Logger LOGGER = LogManager.getRootLogger();
	
	private static boolean initalizedConnection;
	private static int initalizeConnectionSteps = 2;
	
	public static boolean verboseMissingMods() {
		if (initalizedConnection)
			// faster access after initalization
			return true;
		
		else if (--initalizeConnectionSteps < 0)
			// after initalized
			return (initalizedConnection = true);
		
		else
			// haven't finish initalization
			return false;
	}
	
	public static void loadWorlds(MinecraftServer server, String saveName, String worldNameIn, long seed, WorldType type, String generatorOptions) {
        WorldSettings overworldSettings = new WorldSettings(seed, server.getGameType(), server.canStructuresSpawn(), server.isHardcore(), type);
        overworldSettings.setGeneratorOptions(generatorOptions);
        
        Integer[] dimIds = DimensionManager.getStaticDimensionIDs();
        Arrays.sort(dimIds, new Comparator<Integer>(){
            @Override
            public int compare(Integer dim1, Integer dim2) {
                return dim1 == 0 ? -1 : Math.max(dim1, dim2); // Always set dim 0 to the first
            }
        });
        
        // Prepare worlds array with same size
        server.worlds = new WorldServer[dimIds.length];
        
        for (int index = 0; index < dimIds.length; index++) {
            int dim = dimIds[index];
            
            // Skip not allowed nether or end
            if (dim != 0 && (dim == -1 && !server.getAllowNether() || dim == 1 && !server.server.getAllowEnd()))
                continue;
            
            Environment environment = Environment.getEnvironment(dim);
            // Register dimension to forge
            if (!DimensionManager.isDimensionRegistered(dim))
                DimensionManager.registerDimension(dim, DimensionType.getById(environment.getId()));
            
            // Make up world name by dimension
            String worldName = dim == 0 ? saveName : "DIM" + dim;
            ChunkGenerator generator = server.server.getGenerator(worldName);
            
            ISaveHandler saver = new AnvilSaveHandler(server.server.getWorldContainer(), worldName, true, server.dataFixer);
            server.setResourcePackFromWorld(server.getFolderName(), saver);
            
        	WorldInfo info = saver.loadWorldInfo();
            if (info == null)
            	// Workaround: This can be null when manually delete etc,.
            	info = new WorldInfo(overworldSettings, worldName);
            
            // Sync dimension data
            info.dimension = dim;
            
            WorldServer world;
            if (dim == 0) {
                world = (WorldServer) new WorldServer(server, saver, info, dim, server.profiler, environment, generator).init();
                world.initialize(overworldSettings);
                // Initialize server scoreboard
                server.server.scoreboardManager = new CraftScoreboardManager(server, world.getScoreboard());
            } else {
                world = (WorldServer) new WorldServerMulti(server, saver, dim, server.worlds[0], server.profiler, info, environment, generator).init();
            }
            
            // Put world into vanilla worlds
            server.worlds[index] = (WorldServer) world;
            
            world.addEventListener(new ServerWorldEventHandler(server, (WorldServer) world));
            world.getWorldInfo().setGameType(server.getGameType());
            
            // Events
            server.server.getPluginManager().callEvent(new WorldInitEvent(world.getWorld()));
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));
        }
        
        server.getPlayerList().setPlayerManager(server.worlds);
        server.setDifficultyForAllWorlds(server.getDifficulty());
        server.initialWorldChunkLoad();
	}
	
	public static void initializeChunks(MinecraftServer server) {
        for (int index = 0; index < server.worlds.length; index++) {
            WorldServer world = server.worlds[index];
            
            LOGGER.info("Preparing start region for level " + world.dimension + " (Seed: " + world.getSeed() + ")");
            // Skip specificed worlds
            if (!world.getWorld().getKeepSpawnInMemory())
            	continue;
            
            BlockPos spawn = world.getSpawnPoint();
            long start = MinecraftServer.getCurrentTimeMillis();
            
            int chunks = 0;
            for (int x = -192; x <= 192 && server.isServerRunning(); x += 16) {
                for (int z = -192; z <= 192 && server.isServerRunning(); z += 16) {
                    world.getChunkProvider().provideChunk(spawn.getX() + x >> 4, spawn.getZ() + z >> 4);
                    
                    long current = MinecraftServer.getCurrentTimeMillis();
                    if (start - current > 1000) {
                        server.outputPercentRemaining("Preparing spawn area", ++chunks * 100 / 625);
                        start = current;
                    }
                }
            }
        }
        
        for (int index = 0; index < server.worlds.length; index++) {
        	server.server.getPluginManager().callEvent(new WorldLoadEvent(server.worlds[index].getWorld()));
        }
	}
	
	public static void preStopServer(MinecraftServer server) {
        synchronized (server.stopLock) {
            if (server.hasStopped) {
                return;
            }
            server.hasStopped = true;
        }
        
        //WatchdogThread.doStop();
        
        if (server.server != null) {
            server.server.disablePlugins();
        }
	}
	
	public static void stopServer(MinecraftServer server) {
        //if (SpigotConfig.saveUserCacheOnStopOnly)
            server.profileCache.save();
        
        MinecraftServer.LOGGER.info("Stopped server");
	}
	
    private static final int TPS = 20;
    private static final long SEC_IN_NANO = 1000000000;
    public static final long TICK_TIME = SEC_IN_NANO / TPS;
    public static final long MAX_CATCHUP_BUFFER = TICK_TIME * TPS * 60L; // Akarin
    public static final int SAMPLE_INTERVAL = 20; // Akarin
    public final static RollingAverage TPS_1 = new RollingAverage(60);
    public final static RollingAverage TPS_5 = new RollingAverage(60 * 5);
    public final static RollingAverage TPS_15 = new RollingAverage(60 * 15);
	
    public static class RollingAverage {
        private final int size;
        private long time;
        private double total;
        private int index = 0;
        private final double[] samples;
        private final long[] times;

        RollingAverage(int size) {
            this.size = size;
            this.time = size * SEC_IN_NANO;
            this.total = TPS * SEC_IN_NANO * size;
            this.samples = new double[size];
            this.times = new long[size];
            for (int i = 0; i < size; i++) {
                this.samples[i] = TPS;
                this.times[i] = SEC_IN_NANO;
            }
        }

        public void add(double x, long t) {
            time -= times[index];
            total -= samples[index] * times[index];
            samples[index] = x;
            times[index] = t;
            time += t;
            total += x * t;
            if (++index == size) {
                index = 0;
            }
        }

        public double getAverage() {
            return total / time;
        }
    }
	
	public static void tickServer(MinecraftServer server) throws InterruptedException {
        Arrays.fill(server.recentTps, 20);
        long start = System.nanoTime(), lastTick = start - TICK_TIME, catchupTime = 0, curTime, wait, tickSection = start;
        
        while (server.serverRunning) {
            curTime = System.nanoTime();
            wait = TICK_TIME - (curTime - lastTick);
            if (wait > 0) {
                if (catchupTime < 2E6) {
                    wait += Math.abs(catchupTime);
                } else if (wait < catchupTime) {
                    catchupTime -= wait;
                    wait = 0;
                } else {
                    wait -= catchupTime;
                    catchupTime = 0;
                }
            }
            
            if (wait > 0) {
            	long park = System.nanoTime();
            	while ((System.nanoTime() - park) < wait);
                curTime = System.nanoTime();
                wait = TICK_TIME - (curTime - lastTick);
            }
            
            catchupTime = Math.min(MAX_CATCHUP_BUFFER, catchupTime - wait);
            if (++MinecraftServer.currentTick % SAMPLE_INTERVAL == 0) {
                final long diff = curTime - tickSection;
                double currentTps = 1E9 / diff * SAMPLE_INTERVAL;
                
                TPS_1.add(currentTps, diff);
                TPS_5.add(currentTps, diff);
                TPS_15.add(currentTps, diff);
                
                // Backwards compat with bad plugins
                server.recentTps[0] = TPS_1.getAverage();
                server.recentTps[1] = TPS_5.getAverage();
                server.recentTps[2] = TPS_15.getAverage();
                tickSection = curTime;
            }
            lastTick = curTime;

            server.tick();
            server.serverIsRunning = true;
        }
	}
	
	public static void preTick(MinecraftServer server) {
		server.server.getScheduler().mainThreadHeartbeat(server.tickCounter);
        
        while (!server.processQueue.isEmpty()) {
        	server.processQueue.remove().run();
        }
        
        // Send time updates to everyone, it will get the right time from the world the player is in.
        if (server.tickCounter % 20 == 0) {
            for (int i = 0; i < server.getPlayerList().playerEntityList.size(); ++i) {
                EntityPlayerMP entityplayer = (EntityPlayerMP) server.getPlayerList().playerEntityList.get(i);
                entityplayer.connection.sendPacket(new SPacketTimeUpdate(entityplayer.world.getTotalWorldTime(), entityplayer.getPlayerTime(), entityplayer.world.getGameRules().getBoolean("doDaylightCycle")));
            }
        }
	}
	
	public static void tickWorlds(MinecraftServer server) {
		for (int index = 0; index < server.worlds.length; index++) {
			WorldServer world = server.worlds[index];
			net.minecraftforge.fml.common.FMLCommonHandler.instance().onPreWorldTick(world);
			
			try {
				world.tick();
			} catch (Throwable t) {
				CrashReport crashreport = CrashReport.makeCrashReport(t, "Exception ticking world");
				world.addWorldInfoToCrashReport(crashreport);
				throw new ReportedException(crashreport);
			}
			
			try {
				world.updateEntities();
			} catch (Throwable t) {
				CrashReport crashreport1 = CrashReport.makeCrashReport(t, "Exception ticking world entities");
				world.addWorldInfoToCrashReport(crashreport1);
				throw new ReportedException(crashreport1);
			}

			net.minecraftforge.fml.common.FMLCommonHandler.instance().onPostWorldTick(world);
		}
	}
	
	public static void initalizeServer(String[] arguments) {
		OptionSet options = Main.main(arguments);
        if (options == null) {
            return;
        }
        
        try {
            String root = ".";
            
            YggdrasilAuthenticationService yggdrasil = new YggdrasilAuthenticationService(Proxy.NO_PROXY, UUID.randomUUID().toString());
            MinecraftSessionService session = yggdrasil.createMinecraftSessionService();
            GameProfileRepository profile = yggdrasil.createProfileRepository();
            PlayerProfileCache usercache = new PlayerProfileCache(profile, new File(root, MinecraftServer.USER_CACHE_FILE.getName()));
            DedicatedServer server = new DedicatedServer(options, DataFixesManager.createFixer(), yggdrasil, session, profile, usercache);
            
            if (options.has("port")) {
                int port = (Integer) options.valueOf("port");
                if (port > 0) {
                    server.setServerPort(port);
                }
            }
            
            if (options.has("universe")) {
                server.anvilFile = (File) options.valueOf("universe");
            }
            
            if (options.has("world")) {
                server.setFolderName((String) options.valueOf("world"));
            }
            
            server.primaryThread.start();
        } catch (Throwable throwable) {
            LOGGER.fatal("Failed to start the minecraft server", throwable);
        }
	}

	public static void initalizeConfiguration(DedicatedServer dedicatedServer) {
		
	}

	public static void handleServerCommandEvent(MinecraftServer server, PendingCommand command) {
        ServerCommandEvent event = new ServerCommandEvent(server.console, command.command);
        server.server.getPluginManager().callEvent(event);
        
        if (event.isCancelled())
        	return;
        
        command = new PendingCommand(event.getCommand(), command.sender);
        server.server.dispatchServerCommand(server.console, command);
	}

	public static String handleRemoteServerCommandEvent(DedicatedServer server, String command) {
		Waitable<String> waitable = new Waitable<String>() {
            @Override
            protected String evaluate() {
            	server.rconConsoleSource.resetLog();
                RemoteServerCommandEvent event = new RemoteServerCommandEvent(server.remoteConsole, command);
                server.server.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return "";
                }
                PendingCommand serverCommand = new PendingCommand(event.getCommand(), server.rconConsoleSource);
                server.server.dispatchServerCommand(server.remoteConsole, serverCommand);
                return server.rconConsoleSource.getLogContents();
            }
        };
        server.processQueue.add(waitable);
        try {
            return waitable.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Exception processing rcon command " + command, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Maintain interrupted state
            throw new RuntimeException("Interrupted processing rcon command " + command, e);
        }
	}
}
