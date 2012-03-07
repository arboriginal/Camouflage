package me.arboriginal.camouflage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraft.server.MobEffect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.getspout.spoutapi.SpoutManager;
import org.getspout.spoutapi.event.spout.SpoutCraftEnableEvent;
import org.getspout.spoutapi.event.spout.SpoutcraftFailedEvent;
import org.getspout.spoutapi.player.EntitySkinType;
import org.getspout.spoutapi.player.SpoutPlayer;
import org.getspout.spoutapi.plugin.SpoutPlugin;

public class Camouflage extends SpoutPlugin implements Listener {
	protected FileConfiguration	               config;
	protected ArrayList<Integer>	             customMobs;
	protected Map<String, String>	             blocksGroups;
	protected Map<String, Integer>	           composedBlocksGroups;
	protected Map<String, Map<String, String>>	creatureTypes;
	protected boolean	                         activated	= false;
	protected boolean	                         semaphore	= false;
	protected int	                             blockRadius;
	protected int	                             maxAddTries;
	protected int	                             maxDelTries;

	// -----------------------------------------------------------------------------------------------
	// SpoutPlugin related methods
	// -----------------------------------------------------------------------------------------------

	@Override
	public void onEnable() {
		getConfig();
		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void reloadConfig() {
		disableCustomMobs();
		super.reloadConfig();
		initConfig();

		if (getServer().getOnlinePlayers().length > 0) {
			activateCustomMobs();
		}
	}

	@Override
	public void onDisable() {
		disableCustomMobs();
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equals("camouflage-reload")) {
			reloadConfig();
			sender.sendMessage(getName() + "'s configuration has been reload.");

			return true;
		}

		return false;
	}

	// -----------------------------------------------------------------------------------------------
	// Listener related methods
	// -----------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onCreatureSpawn(CreatureSpawnEvent event) {
		Entity entity = event.getEntity();

		if (shouldCustomizeEntity(entity) && customizeEntity(entity)) {
			safeAdd(entity.getEntityId());
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onChunkLoad(ChunkLoadEvent event) {
		for (Entity entity : event.getChunk().getEntities()) {
			if (shouldCustomizeEntity(entity) && customizeEntity(entity)) {
				safeAdd(entity.getEntityId());
			}
		}
	}

	public void onChunkUnload(ChunkUnloadEvent event) {
		for (Entity entity : event.getChunk().getEntities()) {
			if (shouldCustomizeEntity(entity) && customizeEntity(entity)) {
				safeDel(entity.getEntityId());
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onEntityDeath(EntityDeathEvent event) {
		safeDel(event.getEntity().getEntityId());
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (!activated) {
			activateCustomMobs();
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerQuit(PlayerQuitEvent event) {
		if (getServer().getOnlinePlayers().length == 1) {
			disableCustomMobs();
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onSpoutcraftEnabled(SpoutCraftEnableEvent event) {
		if (config.getBoolean("restrictTexturePack")) {
			String texturePackURL = config.getString("texturePackURL");

			if (!texturePackURL.isEmpty()) {
				SpoutPlayer player = event.getPlayer();

				sendDownloadNotification(player);
				player.setTexturePack(config.getString("texturePackURL"));
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onSpoutcraftFailed(SpoutcraftFailedEvent event) {
		SpoutPlayer player = event.getPlayer();

		if (!config.getBoolean("allowNonSpoutCraftUser")) {
			player.kickPlayer(config.getString("nonSpoutCraftKickMessage"));
		}
		else if (config.getBoolean("alertNonSpoutCraftUser")) {
			String message = config.getString("nonSpoutCraftWarnMessage");

			if (!message.isEmpty()) {
				player.sendMessage(message);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
		SpoutPlayer player = (SpoutPlayer) event.getPlayer();

		if (player.hasPermission("camouflage.use")) {
			setCustomPlayerSkin(player, player.isSneaking());
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		SpoutPlayer player = (SpoutPlayer) event.getPlayer();

		if (player.isSneaking()) {
			setCustomPlayerSkin(player, false);
		}
	}

	// -----------------------------------------------------------------------------------------------
	// Custom methods
	// -----------------------------------------------------------------------------------------------

	private void initConfig() {
		String configFile = getDataFolder() + "/config.yml";

		if (new File(configFile).exists()) {
			config = getConfig();
			blockRadius = config.getInt("blockRadius");
			maxAddTries = config.getInt("maxAddTries");
			maxDelTries = config.getInt("maxDelTries");

			parseBlocksGroupsSettings();
			parseCreaturesSettings();
			preloadSkins();
		}
		else {
			try {
				copyJarFile("/config.yml", configFile);
				reloadConfig();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void copyJarFile(String source, String target) throws IOException {
		File destination = new File(target);
		File parentFolder = new File(destination.getParent());

		if (parentFolder.exists() || parentFolder.mkdirs()) {
			InputStream is = getClass().getResourceAsStream(source);
			FileOutputStream os = new FileOutputStream(destination);
			int b;

			while ((b = is.read()) != -1) {
				os.write(b);
			}

			os.close();
			is.close();
			System.out.println("[" + getName() + "] " + destination.getPath() + " created successfully.");
		}
	}

	private void parseBlocksGroupsSettings() {
		blocksGroups = new HashMap<String, String>();
		composedBlocksGroups = new HashMap<String, Integer>();

		if (config.contains("blocksGroups")) {
			Object userChoice = config.get("blocksGroups");

			if (userChoice instanceof MemorySection) {
				Map<String, Object> groups = ((MemorySection) userChoice).getValues(false);

				for (Iterator<?> i = ((Map<?, ?>) groups).keySet().iterator(); i.hasNext();) {
					String blocksGroupName = (String) i.next();

					for (Object block : config.getList("blocksGroups." + blocksGroupName)) {
						if (block instanceof String) {
							blocksGroups.put((String) block, blocksGroupName);

							if (((String) block).contains("+")) {
								String[] composed = ((String) block).split("\\+");
								composedBlocksGroups.put(composed[0], composed.length);
							}
						}
					}
				}
			}
		}
	}

	private void parseCreaturesSettings() {
		creatureTypes = new HashMap<String, Map<String, String>>();

		List<String> types = new ArrayList<String>();
		types.add("PLAYER");

		for (CreatureType type : CreatureType.values()) {
			types.add(type.toString());
		}

		for (String type : types) {
			if (config.contains("creatures." + type)) {
				Object userChoice = config.get("creatures." + type);

				if (userChoice instanceof MemorySection) {
					Map<String, Object> skins = ((MemorySection) userChoice).getValues(false);
					Map<String, String> creatureSkins = new HashMap<String, String>();

					for (Iterator<?> i = ((Map<?, ?>) skins).keySet().iterator(); i.hasNext();) {
						String blocksGroupName = (String) i.next();
						Object skinCodeName = ((Map<?, ?>) skins).get(blocksGroupName);

						if (skinCodeName instanceof String && config.contains("skins." + skinCodeName)) {
							creatureSkins.put(blocksGroupName, config.getString("skins." + skinCodeName));
						}
					}

					creatureTypes.put(getCreatureClassName(type), creatureSkins);
				}
			}
		}
	}

	private String getCreatureClassName(String creatureType) {
		String className = (creatureType == "PLAYER") ? "SpoutCraft" : "Craft";

		for (String part : creatureType.split("_")) {
			className += part.substring(0, 1).toUpperCase() + part.substring(1).toLowerCase();
		}

		return className;
	}

	private void preloadSkins() {
		SpoutManager.getFileManager().removeFromCache(this, SpoutManager.getFileManager().getCache(this));

		if (config.contains("skins")) {
			Object userChoice = config.get("skins");

			if (userChoice instanceof MemorySection) {
				Map<String, Object> skins = ((MemorySection) userChoice).getValues(false);

				for (Iterator<?> i = ((Map<?, ?>) skins).keySet().iterator(); i.hasNext();) {
					Object skinUrl = ((Map<?, ?>) skins).get(i.next());

					if (skinUrl instanceof String) {
						SpoutManager.getFileManager().addToPreLoginCache(this, (String) skinUrl);
					}
				}
			}
		}
	}

	private void activateCustomMobs() {
		activated = true;
		Long updatePeriod = config.getLong("updatePeriod");

		if (updatePeriod > 0) {
			registerCustomMobs();

			getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
				@Override
				public void run() {
					if (activated && !semaphore) {
						semaphore = true;
						ArrayList<?> mobsList = (ArrayList<?>) customMobs.clone();
						semaphore = false;

						for (Object entityId : mobsList) {
							Entity entity = SpoutManager.getEntityFromId((Integer) entityId);

							if (entity == null || entity.isDead()) {
								safeDel((Integer) entityId);
							}
							else {
								customizeEntity(entity);
							}
						}
					}
				}
			}, 1L, updatePeriod);
		}
	}

	private void disableCustomMobs() {
		getServer().getScheduler().cancelTasks(this);

		activated = false;
	}

	private void registerCustomMobs() {
		customMobs = new ArrayList<Integer>();

		for (World world : getServer().getWorlds()) {
			for (Entity entity : world.getEntities()) {
				if (shouldCustomizeEntity(entity) && customizeEntity(entity)) {
					safeAdd(entity.getEntityId());
				}
			}
		}

		semaphore = false;
	}

	private void safeAdd(int entityId) {
		int attempts = 0;

		while (semaphore) {
			attempts++;

			if (attempts > maxAddTries) {
				return;
			}
		}

		semaphore = true;
		if ((Object) entityId != null && !customMobs.contains(entityId)) {
			customMobs.add(entityId);
		}
		semaphore = false;
	}

	private void safeDel(int entityId) {
		int attempts = 0;

		while (semaphore) {
			attempts++;

			if (attempts > maxDelTries) {
				return;
			}
		}

		semaphore = true;
		if ((Object) entityId != null) {
			customMobs.remove((Object) entityId);
		}
		semaphore = false;
	}

	private void setCustomPlayerSkin(SpoutPlayer player, boolean reset) {
		if (reset) {
			player.resetSkin();

			if (config.getInt("predatorView") > 0) {
				((CraftPlayer) player).getHandle().addEffect(new MobEffect(1, 0, 0));
			}
		}
		else {
			String skinUrl = getEntitySkin(player);

			if (skinUrl != null) {
				int predatorView = config.getInt("predatorView");

				if (predatorView > 0) {
					((CraftPlayer) player).getHandle().addEffect(new MobEffect(1, 1, predatorView));
					((CraftPlayer) player).getHandle().reset();
				}

				player.setSkin(skinUrl);
			}
		}
	}

	private boolean customizeEntity(Entity entity) {
		if (activated) {
			String customSkin = getEntitySkin(entity);

			if (customSkin != null) {
				getSpoutServer().setEntitySkin((LivingEntity) entity, customSkin, getEntitySkinType(entity));
				return true;
			}
		}

		return false;
	}

	private EntitySkinType getEntitySkinType(Entity entity) {
		if (entity instanceof Sheep && !((Sheep) entity).isSheared()) {
			return EntitySkinType.SHEEP_FUR;
		}
		if (entity instanceof Pig && ((Pig) entity).hasSaddle()) {
			return EntitySkinType.PIG_SADDLE;
		}
		if (entity instanceof Wolf) {
			if (((Wolf) entity).isAngry()) {
				return EntitySkinType.WOLF_ANGRY;
			}
			if (((Wolf) entity).isTamed()) {
				return EntitySkinType.WOLF_TAMED;
			}
		}

		return EntitySkinType.DEFAULT;
	}

	private boolean shouldCustomizeEntity(Entity entity) {
		return activated && entity != null && creatureTypes.containsKey(entity.getClass().getSimpleName());
	}

	private String getEntitySkin(Entity entity) {
		Block masterBlock = getMasterBlock(entity.getWorld(), entity.getLocation());
		String blockGroup;

		if (blockRadius > 0) {
			blockGroup = getBlockssAround(masterBlock, blockRadius).get(0).getKey();
		}
		else {
			blockGroup = getBlocksGroup(masterBlock);
		}

		return creatureTypes.get(entity.getClass().getSimpleName()).get(blockGroup);
	}

	private String getBlocksGroup(Block block) {
		String blockType = block.getType().toString();
		String blocksGroup = blocksGroups.get(blockType + ":" + block.getData());

		if (blocksGroup == null) {
			blocksGroup = blocksGroups.get(blockType);
		}

		return blocksGroup;
	}

	private Block getMasterBlock(World world, Location location) {
		Block masterBlock = world.getBlockAt(location);

		while (masterBlock.getType().equals(Material.AIR)) {
			masterBlock = masterBlock.getRelative(BlockFace.DOWN);
		}

		return masterBlock;
	}

	private List<Entry<String, Integer>> getBlockssAround(Block block, int radius) {
		HashMap<String, Integer> materials = new LinkedHashMap<String, Integer>();

		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				Block around = getSolidBlockAround(block.getRelative(x, 0, z));

				if (around != null) {
					String blocksGroup = getBlocksGroup(around);
					materials.put(blocksGroup, materials.containsKey(blocksGroup) ? materials.get(blocksGroup) + 1 : 1);
				}
			}
		}

		List<Entry<String, Integer>> blocks = new ArrayList<Entry<String, Integer>>(materials.entrySet());

		Collections.sort(blocks, new Comparator<Entry<String, Integer>>() {
			@Override
			public int compare(Entry<String, Integer> o1, Entry<String, Integer> o2) {
				return o2.getValue() - o1.getValue();
			}
		});

		return blocks;
	}

	private Block getSolidBlockAround(Block block) {
		if (block.getType().equals(Material.AIR)) {
			return null;
		}

		Block around = block.getRelative(BlockFace.UP);
		return around.getType().equals(Material.AIR) ? block : around;
	}

	private void sendDownloadNotification(SpoutPlayer player) {
		String title = config.getString("texturePackNotificationTitle");
		String message = config.getString("texturePackNotificationMessage");
		Material material = Material.matchMaterial(config.getString("texturePackNotificationItem"));

		if (title.length() > 26) {
			title = title.substring(0, 26);
		}

		if (message.length() > 26) {
			message = message.substring(0, 26);
		}

		if (material == null) {
			material = Material.MAP;
		}

		player.sendNotification(title, message, material);
	}
}
