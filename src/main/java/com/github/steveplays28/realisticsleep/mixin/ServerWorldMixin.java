package com.github.steveplays28.realisticsleep.mixin;

import com.github.steveplays28.realisticsleep.SleepMath;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.SleepManager;
import net.minecraft.text.Text;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.GameRules;
import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.ServerWorldProperties;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.github.steveplays28.realisticsleep.RealisticSleep.config;
import static com.github.steveplays28.realisticsleep.SleepMath.DAY_LENGTH;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin extends World {
	@Unique
	public double nightTimeStepPerTick = 1;
	@Unique
	public int nightTimeStepPerTickRounded = 1;
	@Unique
	public long tickDelay;
	@Unique
	public String sleepMessage;
	@Unique
	public Boolean shouldSkipWeather = false;

	@Shadow
	@Final
	protected RaidManager raidManager;
	@Shadow
	@Final
	List<ServerPlayerEntity> players;
	@Shadow
	@Final
	private ServerWorldProperties worldProperties;
	@Shadow
	@Final
	private MinecraftServer server;
	@Shadow
	@Final
	private SleepManager sleepManager;
	@Shadow
	@Final
	private ServerChunkManager chunkManager;
	@Shadow
	@Final
	private boolean shouldTickTime;

	protected ServerWorldMixin(MutableWorldProperties properties, RegistryKey<World> registryRef, RegistryEntry<DimensionType> registryEntry, Supplier<Profiler> profiler, boolean isClient, boolean debugWorld, long seed, int maxChainedNeighborUpdates) {
		super(properties, registryRef, registryEntry, profiler, isClient, debugWorld, seed, maxChainedNeighborUpdates);
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/GameRules;getInt(Lnet/minecraft/world/GameRules$Key;)I"))
	public void tickInject(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		int sleepingPlayerCount = sleepManager.getSleeping();
		// TODO: Don't assume the TPS is 20
		int secondsUntilAwake = Math.abs(
				SleepMath.calculateSecondsUntilAwake((int) worldProperties.getTimeOfDay() % 24000, nightTimeStepPerTick, 20));

		// Check if the night has (almost) ended and the weather should be skipped
		if (secondsUntilAwake <= 2 && shouldSkipWeather) {
			clearWeather();
			shouldSkipWeather = false;
		}

		// Check if anyone is sleeping
		if (sleepingPlayerCount <= 0) {
			return;
		}

		// Fetch config values and do calculations
		int playerCount = server.getCurrentPlayerCount();
		double sleepingRatio = (double) sleepingPlayerCount / playerCount;
		double sleepingPercentage = sleepingRatio * 100;

		nightTimeStepPerTick = SleepMath.calculateNightTimeStepPerTick(sleepingRatio, config.sleepSpeedMultiplier, nightTimeStepPerTick);
		nightTimeStepPerTickRounded = (int) Math.round(nightTimeStepPerTick);
		var nightOrDayText = worldProperties.getTimeOfDay() > DAY_LENGTH / 2 ? "night" : "day";

		int blockEntityTickSpeedMultiplier = (int) Math.round(config.blockEntityTickSpeedMultiplier);
		int chunkTickSpeedMultiplier = (int) Math.round(config.chunkTickSpeedMultiplier);
		int raidTickSpeedMultiplier = (int) Math.round(config.raidTickSpeedMultiplier);

		boolean doDayLightCycle = server.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE);
		int playersRequiredToSleepPercentage = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
		double playersRequiredToSleepRatio = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE) / 100;
		int playersRequiredToSleep = (int) Math.ceil(playersRequiredToSleepRatio * playerCount);

		// Check if the required percentage of players are sleeping
		if (sleepingPercentage < playersRequiredToSleepPercentage) {
			if (!config.sendNotEnoughPlayersSleepingMessage) {
				return;
			}

			for (ServerPlayerEntity player : players) {
				player.sendMessage(
						Text.of(sleepingPlayerCount + "/" + playerCount + " players are currently sleeping. " + playersRequiredToSleep + "/" + playerCount + " players are required to sleep through the " + nightOrDayText + "."),
						true
				);
			}

			return;
		}

		// Advance time
		if (doDayLightCycle) {
			worldProperties.setTimeOfDay(worldProperties.getTimeOfDay() + nightTimeStepPerTickRounded);
		}

		// Tick block entities
		for (int i = blockEntityTickSpeedMultiplier; i > 1; i--) {
			this.tickBlockEntities();
		}

		// Tick chunks
		for (int i = chunkTickSpeedMultiplier; i > 1; i--) {
			chunkManager.tick(shouldKeepTicking, true);
		}

		// Tick raid timers
		for (int i = raidTickSpeedMultiplier; i > 1; i--) {
			raidManager.tick();
		}

		// Send new time to all players in the overworld
		server.getPlayerManager().sendToDimension(
				new WorldTimeUpdateS2CPacket(worldProperties.getTime(), worldProperties.getTimeOfDay(), doDayLightCycle), getRegistryKey());

		// Check if players are still supposed to be sleeping, and send a HUD message if so
		if (secondsUntilAwake > 0) {
			shouldSkipWeather = true;

			if (config.sendSleepingMessage) {
				sleepMessage = String.format("%d/%d players are sleeping through this %s", sleepingPlayerCount, playerCount,
						worldProperties.isThundering() ? "thunderstorm" : nightOrDayText
				);
				if (config.showTimeUntilDawn) sleepMessage += String.format(" (Time until dawn: %d", secondsUntilAwake) + "s)";

				for (ServerPlayerEntity player : players) {
					player.sendMessage(Text.of(sleepMessage), true);
				}
			}
		}
	}

	/**
	 * @author Steveplays28
	 * @reason Method's HUD messages conflicts with my custom HUD messages
	 */
	@Overwrite
	private void sendSleepingStatus() {
	}

	@Inject(method = "tickTime", at = @At(value = "HEAD"), cancellable = true)
	public void tickTimeInject(CallbackInfo ci) {
		this.worldProperties.getScheduledEvents().processEvents(this.server, this.properties.getTime());

		if (!this.shouldTickTime) {
			ci.cancel();
			return;
		}

		long l = this.properties.getTime() + 1L;
		if (sleepManager.getSleeping() <= 0) {
			this.worldProperties.setTime(l);
		}

		if (tickDelay > 0L) {
			tickDelay -= 1L;
			server.getPlayerManager().sendToDimension(
					new WorldTimeUpdateS2CPacket(worldProperties.getTime(), worldProperties.getTimeOfDay(),
							this.properties.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)
					), getRegistryKey());

			ci.cancel();
			return;
		}

		if (sleepManager.getSleeping() > 0) {
			return;
		}

		if (this.properties.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
			this.worldProperties.setTimeOfDay(this.properties.getTimeOfDay() + 1L);
		}

		tickDelay = config.tickDelay;

		ci.cancel();
	}

	@Unique
	private void clearWeather() {
		boolean doWeatherCycle = server.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE);

		if (doWeatherCycle && (worldProperties.isRaining() || worldProperties.isThundering())) {
			// Reset weather clock and clear weather
			var nextRainTime = (int) (DAY_LENGTH * SleepMath.getRandomNumberInRange(0.5, 7.5));
			worldProperties.setRainTime(nextRainTime);
			worldProperties.setThunderTime(nextRainTime + (Math.random() > 0 ? 1 : -1));

			worldProperties.setThundering(false);
			worldProperties.setRaining(false);
		}
	}
}
