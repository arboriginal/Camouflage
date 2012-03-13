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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creature;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.getspout.spoutapi.SpoutManager;
import org.getspout.spoutapi.event.input.KeyBindingEvent;
import org.getspout.spoutapi.event.inventory.InventoryCloseEvent;
import org.getspout.spoutapi.event.spout.SpoutCraftEnableEvent;
import org.getspout.spoutapi.event.spout.SpoutcraftFailedEvent;
import org.getspout.spoutapi.gui.GenericItemWidget;
import org.getspout.spoutapi.gui.GenericLabel;
import org.getspout.spoutapi.gui.GenericTexture;
import org.getspout.spoutapi.gui.ScreenType;
import org.getspout.spoutapi.gui.Widget;
import org.getspout.spoutapi.gui.WidgetAnchor;
import org.getspout.spoutapi.keyboard.BindingExecutionDelegate;
import org.getspout.spoutapi.keyboard.Keyboard;
import org.getspout.spoutapi.player.EntitySkinType;
import org.getspout.spoutapi.player.SpoutPlayer;
import org.getspout.spoutapi.plugin.SpoutPlugin;

public class Camouflage extends SpoutPlugin implements Listener, BindingExecutionDelegate {
	protected FileConfiguration	               config;
	protected Map<String, String>	             blocksGroups;
	protected Map<String, Integer>	           composedBlocksGroups;
	protected Map<String, Map<String, String>>	creatureTypes;
	protected Map<String, Map<String, Float>>	 dupeCreatures;
	protected Map<String, Boolean>	           camouflaged	= new HashMap<String, Boolean>();
	protected ArrayList<Integer>	             customMobs	 = new ArrayList<Integer>();
	protected ArrayList<String>	               microTasks	 = new ArrayList<String>();
	protected boolean	                         activated	 = false;
	protected boolean	                         semaphore	 = false;
	protected int	                             energy	     = 0;
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
		SpoutManager.getKeyBindingManager() //
		    .registerBinding("CamouflageToggleKey", Keyboard.KEY_C, "Toggle camouflage", this, this);
	}

	@Override
	public void reloadConfig() {
		disableCustomMobs();
		super.reloadConfig();
		initConfig();

		if (getServer().getOnlinePlayers().length > 0) {
			activateCustomMobs();
			refreshHUD();
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
	// BindingExecutionDelegate related methods
	// -----------------------------------------------------------------------------------------------

	@Override
	public void keyPressed(KeyBindingEvent event) {
	}

	@Override
	public void keyReleased(KeyBindingEvent event) {
		if (event.getBinding().getId().equals("CamouflageToggleKey")
		    && event.getScreenType().equals(ScreenType.GAME_SCREEN) //
		) {
			SpoutPlayer player = (SpoutPlayer) event.getPlayer();

			if (player.hasPermission("camouflage.use.activate")) {
				String key = event.getPlayer().getName();
				boolean is_camouflaged = !camouflaged.get(key);

				camouflaged.put(key, is_camouflaged);

				if (is_camouflaged) {
					buildInterface(player);
				}
				else {
					player.getMainScreen().removeWidgets(this);
				}
			}
		}
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
		camouflaged.put(event.getPlayer().getName(), false);

		if (!activated) {
			activateCustomMobs();
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerQuit(PlayerQuitEvent event) {
		SpoutPlayer player = (SpoutPlayer) event.getPlayer();
		player.getMainScreen().removeWidgets(this);
		camouflaged.remove(player.getName());

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

		if (camouflaged.get(player.getName())) {
			setCustomPlayerSkin(player, player.isSneaking(), true);
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event) {
		SpoutPlayer player = (SpoutPlayer) event.getPlayer();

		if (isCamouflaged(player)) {
			if (player.hasPermission("camouflage.use.move")) {
				if (player.hasPermission("camouflage.use.update")) {
					setCustomPlayerSkin(player, false);
				}
			}
			else if (playerHasMoved(event.getFrom(), event.getTo())) {
				setCustomPlayerSkin(player, true);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityTarget(EntityTargetEvent event) {
		if (event.getTarget() instanceof Player && dupableReason(event.getReason(), event)) {
			Player player = (Player) event.getTarget();

			if (isCamouflaged(player)) {
				if (canDupe(player) && creatureIsDuped(event.getEntity(), player)) {
					event.setCancelled(player.hasPermission("camouflage.use.dupe.arrow")
					    || !event.getReason().equals(TargetReason.TARGET_ATTACKED_ENTITY));
				}
				else {
					playSoundEffect(((SpoutPlayer) player), "found");
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerDropItem(PlayerDropItemEvent event) {
		if (event.getItemDrop().getItemStack().getTypeId() == energy) {
			requestUpdateCounter((SpoutPlayer) event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerPickupItem(PlayerPickupItemEvent event) {
		if (event.getItem().getItemStack().getTypeId() == energy) {
			requestUpdateCounter((SpoutPlayer) event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryClose(InventoryCloseEvent event) {
		requestUpdateCounter((SpoutPlayer) event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getPlayer().getItemInHand().getTypeId() == energy) {
			requestUpdateCounter((SpoutPlayer) event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
		if (event.getEntity() instanceof Creature) {
			Entity player = event.getDamager();
			String reason = "player";

			if (player instanceof Arrow) {
				player = ((Arrow) player).getShooter();
				reason = "arrow";
			}

			if (player instanceof Player && isCamouflaged((Player) player)) {
				Entity target = ((Creature) event.getEntity()).getTarget();

				if (target == null || target.getEntityId() != player.getEntityId()) {
					double critical = criticalBlow((SpoutPlayer) player, reason);
					
					if (critical != 0) {
						event.setDamage((int) Math.round(event.getDamage() * critical));
					}
				}
			}
		}
	}

	private double criticalBlow(SpoutPlayer player, String reason) {
		double random = Math.random() * 100;

		for (String blow : ((MemorySection) config.get("blows." + reason)).getValues(false).keySet()) {
			if (config.getDouble("blows." + reason + "." + blow + ".percent") > random) {
				String soundUrl = config.getString("blows." + reason + "." + blow + ".sound");
				
				if (!soundUrl.isEmpty()) {
					SpoutManager.getSoundManager().playCustomSoundEffect(this, player, soundUrl, false);
				}
				
				sendNotification(player, config.getString("blows." + reason + "." + blow + ".title"), //
				    config.getString("blows." + reason + "." + blow + ".message"), //
				    (reason.equals("arrow")) ? Material.BOW : Material.IRON_SWORD);
				
				return config.getDouble("blows." + reason + "." + blow + ".multiplier");
			}
		}

		return 0;
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

			Material userChoice = Material.matchMaterial(config.getString("energy.material"));
			energy = (userChoice != null) ? userChoice.getId() : 0;

			parseBlocksGroupsSettings();
			parseCreaturesSettings();
			preloadSkins();
			preloadHUD();
		}
		else {
			try {
				copyJarFile("/default_config.yml", configFile);
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
		dupeCreatures = new HashMap<String, Map<String, Float>>();

		List<String> types = new ArrayList<String>();
		types.add("PLAYER");

		for (CreatureType creatureType : CreatureType.values()) {
			String type = creatureType.toString();

			dupeCreatures.put(getCreatureClassName(type), parseDupeCreatureSettings(type));
			types.add(type);
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

	private Map<String, Float> parseDupeCreatureSettings(String type) {
		String[] options = { "farDistance", "farAngle", "nearDistance", "nearAngle" };
		Map<String, Float> values = new HashMap<String, Float>();

		for (String option : options) {
			String key = config.contains("dupeCreatures." + type + "." + option) ? type : "default";
			float value = (float) config.getDouble("dupeCreatures." + key + "." + option);

			values.put(option, option.matches("[a-z]+Angle$") ? (float) Math.toRadians(value / 2) : value);
		}

		return values;
	}

	private void preloadSkins() {
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

	private void preloadHUD() {
		String[] images = { "left.active", "left.inactive", "right.active", "right.inactive" };

		for (String image : images) {
			SpoutManager.getFileManager().addToPreLoginCache(this, config.getString("interface." + image + ".image"));
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

	private void buildInterface(SpoutPlayer player) {
		GenericTexture widgetLeft = new GenericTexture(config.getString("interface.left.inactive.image"));
		widgetLeft.setAnchor(WidgetAnchor.TOP_LEFT) //
		    .setWidth(config.getInt("interface.left.inactive.width")) //
		    .setHeight(config.getInt("interface.left.inactive.height"));

		GenericTexture widgetRight = new GenericTexture(config.getString("interface.right.inactive.image"));
		widgetRight.setAnchor(WidgetAnchor.TOP_RIGHT) //
		    .setX(-config.getInt("interface.right.inactive.width")) //
		    .setWidth(config.getInt("interface.right.inactive.width")) //
		    .setHeight(config.getInt("interface.right.inactive.height"));

		player.getMainScreen().attachWidgets(this, widgetLeft, widgetRight);

		if (energy > 0 && !player.hasPermission("camouflage.use.free")) {
			int size = config.getInt("interface.item.size");

			GenericItemWidget item = new GenericItemWidget();
			item.setTypeId(energy).setWidth(size).setHeight(size).setDepth(size).setAnchor(WidgetAnchor.TOP_LEFT) //
			    .setX(config.getInt("interface.item.posX")).setY(config.getInt("interface.item.posY"));

			GenericLabel counter = new GenericLabel();
			counter.setText(getPlayerCounter(player) + "").setMinWidth(1).setMinHeight(1).setAnchor(WidgetAnchor.TOP_LEFT) //
			    .setX(config.getInt("interface.counter.posX")).setY(config.getInt("interface.counter.posY"));

			player.getMainScreen().attachWidgets(this, item, counter);
		}
	}

	private void refreshHUD() {
		for (String name : camouflaged.keySet()) {
			SpoutPlayer player = getSpoutServer().getPlayer(name);

			player.getMainScreen().removeWidgets(this);
			buildInterface(player);
		}
	}

	private int getPlayerCounter(Player player) {
		int quantity = 0;
		HashMap<Integer, ? extends ItemStack> stacks = player.getInventory().all(energy);

		for (Iterator<Integer> i = stacks.keySet().iterator(); i.hasNext();) {
			quantity += stacks.get(i.next()).getAmount();
		}

		return quantity;
	}

	private void requestUpdateCounter(SpoutPlayer player) {
		requestUpdateCounter(player, 10);
	}

	private void requestUpdateCounter(SpoutPlayer player, int delay) {
		if (energy > 0 && camouflaged.get(player.getName())) {
			String lockKey = player.getName() + "---COUNTER";

			if (!microTasks.contains(lockKey)) {
				microTasks.add(lockKey);

				getServer().getScheduler().scheduleAsyncDelayedTask(this, new microTasksUnlock(lockKey, player), delay);
			}
		}
	}

	private void updateCounter(SpoutPlayer player, String notice) {
		for (Widget widget : player.getMainScreen().getAttachedWidgets()) {
			if (widget.getPlugin().equals(this) && widget instanceof GenericLabel) {
				((GenericLabel) widget).setText(getPlayerCounter(player) + notice);

				return;
			}
		}
	}

	private boolean playerHasMoved(Location from, Location to) {
		return from.getBlockX() != to.getBlockX()//
		    || from.getBlockY() != to.getBlockY()//
		    || from.getBlockZ() != to.getBlockZ();
	}

	private boolean isCamouflaged(Player player) {
		return camouflaged.get(player.getName()) && player.isSneaking()
		    && (player.hasPermission("camouflage.use.free") || microTasks.contains(player.getName() + "---CONSUMME"));
	}

	private boolean dupableReason(TargetReason reason, EntityTargetEvent event) {
		switch (reason) {
			case CLOSEST_PLAYER:
			case RANDOM_TARGET:
				return true;
			case TARGET_ATTACKED_ENTITY:
				return event.getEntity().getLastDamageCause().getCause().equals(DamageCause.PROJECTILE);
			default:
				return false;
		}
	}

	private boolean canDupe(Player player) {
		return player.hasPermission("camouflage.use.dupe.far") //
		    || player.hasPermission("camouflage.use.dupe.near") //
		    || player.hasPermission("camouflage.use.dupe.arrow");
	}

	private boolean creatureIsDuped(Entity creature, Player player) {
		Location playerPos = player.getEyeLocation().clone();
		Location creaturePos = creature.getLocation().clone();
		double distance = playerPos.distance(creaturePos);
		Map<String, Float> values = dupeCreatures.get(creature.getClass().getSimpleName());

		if (distance > values.get("farDistance")) {
			return true;
		}

		if (distance > values.get("nearDistance")) {
			return player.hasPermission("camouflage.use.dupe.far")
			    && !creatureCanSeePlayer(creaturePos, playerPos, distance, values.get("farAngle"));
		}

		return player.hasPermission("camouflage.use.dupe.near")
		    && !creatureCanSeePlayer(creaturePos, playerPos, distance, values.get("nearAngle"));
	}

	private boolean creatureCanSeePlayer(Location creaturePos, Location playerPos, double distance, float angle) {
		Vector vector = playerPos.subtract(creaturePos).toVector();

		if (creaturePos.getDirection().angle(vector) > angle) {
			return false;
		}

		World world = playerPos.getWorld();
		vector = vector.normalize();

		for (int i = 1; i < distance - 1; i++) {
			creaturePos.add(vector);
			Block block = world.getBlockAt(creaturePos);

			if (!block.getType().equals(Material.AIR) && !block.getType().equals(Material.GLASS)
			    && !block.getType().equals(Material.THIN_GLASS)) {
				return false;
			}
		}

		return true;
	}

	private void setCustomPlayerSkin(SpoutPlayer player, boolean reset, boolean activate) {
		if (activate && !reset) {
			boolean can = (energy == 0 || getPlayerCounter(player) > 0 || player.hasPermission("camouflage.use.free"));

			playSoundEffect(player, can ? "activate" : "empty");

			if (can) {
				activateConsummation(player);
			}
			else {
				String warning = config.getString("energy.warning");

				if (!warning.isEmpty()) {
					updateCounter(player, " <-- " + warning.replace("<energy>", Material.getMaterial(energy).name()));
					requestUpdateCounter(player, 60);
				}

				return;
			}
		}

		setCustomPlayerSkin(player, reset);
	}

	private void activateConsummation(Player player) {
		if (energy == 0 || player.hasPermission("camouflage.use.free")) {
			return;
		}

		consummeOneEnergyItem(player);

		int frequency = config.getInt("energy.frequency");
		String lockKey = player.getName() + "---CONSUMME";

		if (frequency > 0 && !microTasks.contains(lockKey)) {
			microTasks.add(lockKey);
			delayedConsummation(lockKey, player, frequency);
		}
	}

	private void delayedConsummation(String lockKey, Player player, int frequency) {
		getServer().getScheduler().scheduleAsyncDelayedTask(this, new microTasksUnlock(lockKey, player, frequency),
		    frequency);
	}

	private void consummeOneEnergyItem(Player player) {
		HashMap<Integer, ? extends ItemStack> stacks = player.getInventory().all(energy);
		Iterator<Integer> i = stacks.keySet().iterator();
		ItemStack stack = stacks.get(i.next());
		int amount = stack.getAmount();

		if (amount == 1) {
			player.getInventory().removeItem(stack);
		}
		else {
			stack.setAmount(amount - 1);
		}

		requestUpdateCounter((SpoutPlayer) player);
	}

	private void playSoundEffect(SpoutPlayer player, String key) {
		String soundUrl = config.getString(key + "SoundUrl");

		if (soundUrl != null) {
			String lockKey = player.getName() + "---" + key;

			if (!microTasks.contains(lockKey)) {
				microTasks.add(lockKey);
				int cooldown = config.contains(key + "SoundCooldown") ? config.getInt(key + "SoundCooldown") : 20;

				SpoutManager.getSoundManager().playCustomSoundEffect(this, player, soundUrl, false);
				getServer().getScheduler().scheduleAsyncDelayedTask(this, new microTasksUnlock(lockKey), cooldown);
			}
		}
	}

	private void setCustomPlayerSkin(SpoutPlayer player, boolean reset) {
		int predatorView = config.getInt("predatorView");
		String skinUrl;

		if (reset) {
			microTasks.remove(player.getName() + "---CONSUMME");

			skinUrl = player.getSkin();
			player.resetSkin();

			if (!player.getSkin().equals(skinUrl)) {
				playSoundEffect(player, "inactivate");
			}

			if (config.getInt("predatorView") > 0) {
				((CraftPlayer) player).getHandle().addEffect(new MobEffect(1, 0, 0));
			}
		}
		else {
			skinUrl = getEntitySkin(player);

			if (!player.getSkin().equals(skinUrl)) {
				if (predatorView > 0) {
					((CraftPlayer) player).getHandle().addEffect(new MobEffect(1, 1, predatorView));
					((CraftPlayer) player).getHandle().reset();
				}

				if (skinUrl != null) {
					player.setSkin(skinUrl);
				}
			}
		}

		switchHUDstate(player, reset);
	}

	private void switchHUDstate(SpoutPlayer player, boolean reset) {
		for (Widget widget : player.getMainScreen().getAttachedWidgets()) {
			if (widget.getPlugin().equals(this) && widget instanceof GenericTexture) {
				String side = null;

				if (widget.getAnchor().equals(WidgetAnchor.TOP_LEFT)) {
					side = "left.";
				}
				else if (widget.getAnchor().equals(WidgetAnchor.TOP_RIGHT)) {
					side = "right.";
				}
				else {
					return;
				}

				String key = "interface." + side + (reset ? "inactive" : "active");
				String image = config.getString(key + ".image");

				if (!((GenericTexture) widget).getUrl().equals(image)) {
					((GenericTexture) widget).setUrl(image) //
					    .setX(side.equals("right.") ? -config.getInt(key + ".width") : 0) //
					    .setWidth(config.getInt(key + ".width")).setHeight(config.getInt(key + ".height"));
				}
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

		if (material == null) {
			material = Material.MAP;
		}

		sendNotification(player, title, message, material);
	}

	private void sendNotification(SpoutPlayer player, String title, String message, Material material) {
		if (title.isEmpty() && message.isEmpty()) {
			return;
		}

		if (title.length() > 26) {
			title = title.substring(0, 26);
		}

		if (message.length() > 26) {
			message = message.substring(0, 26);
		}

		player.sendNotification(title, message, material);
	}

	// -----------------------------------------------------------------------------------------------
	// Custom classes
	// -----------------------------------------------------------------------------------------------

	private class microTasksUnlock implements Runnable {
		private final String	key;
		private final Player	player;
		private final int		 freq;

		public microTasksUnlock(String key) {
			this(key, null, 0);
		}

		public microTasksUnlock(String key, Player player) {
			this(key, player, 0);
		}

		public microTasksUnlock(String key, Player player, int freq) {
			this.key = key;
			this.player = player;
			this.freq = freq;
		}

		@Override
		public void run() {
			if (player != null) {
				if (freq > 0) {
					if (microTasks.contains(player.getName() + "---CONSUMME")) {
						consummeOneEnergyItem(player);

						if (getPlayerCounter(player) > 0) {
							delayedConsummation(key, player, freq);
						}
						else {
							microTasks.remove(key);
							setCustomPlayerSkin((SpoutPlayer) player, true, true);
						}
					}

					return;
				}

				updateCounter((SpoutPlayer) player, "");
			}

			microTasks.remove(key);
		}
	}
}
