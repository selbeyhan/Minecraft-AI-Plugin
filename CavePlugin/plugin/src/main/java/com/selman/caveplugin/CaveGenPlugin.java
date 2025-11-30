package com.selman.caveplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CaveGenPlugin extends JavaPlugin {

    // JSON-matching structures
    private static class Voxel {
        int x;
        int y;
        int z;
    }

    private static class CaveSample {
        int sample_id;
        int[] shape;
        int num_voxels;
        int num_cave_voxels;
        List<Voxel> voxels;
    }

    private final Gson gson = new Gson();
    private final List<CaveSample> samples = new ArrayList<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        int count = loadCaves();
        if (count == 0) {
            getLogger().warning("No cave samples loaded on startup.");
        } else {
            getLogger().info("Loaded " + count + " cave samples on startup.");
        }
    }

    /**
     * Loads all .json files in the plugin data folder into the samples list.
     * Returns the number of cave samples loaded.
     * NOTE: This is for pregenerated caves, not the realtime one.
     */
    private int loadCaves() {
        samples.clear();

        File folder = getDataFolder();
        File[] jsonFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".json") && !name.equalsIgnoreCase("realtime_cave.json"));

        if (jsonFiles == null || jsonFiles.length == 0) {
            getLogger().warning("No pregenerated .json files found in " + folder.getAbsolutePath());
            return 0;
        }

        int totalLoaded = 0;
        Type arrType = TypeToken.getArray(CaveSample.class).getType();

        for (File jsonFile : jsonFiles) {
            getLogger().info("Loading caves from " + jsonFile.getName());
            try (FileReader reader = new FileReader(jsonFile)) {
                CaveSample[] fileSamples = gson.fromJson(reader, arrType);
                if (fileSamples != null) {
                    for (CaveSample cs : fileSamples) {
                        samples.add(cs);
                        totalLoaded++;
                    }
                }
            } catch (IOException e) {
                getLogger().severe("Error reading " + jsonFile.getName() + ": " + e.getMessage());
            }
        }

        getLogger().info("Loaded " + totalLoaded + " pregenerated cave samples from " + jsonFiles.length + " file(s).");
        return totalLoaded;
    }

    /**
     * Load a single cave sample from a JSON file that contains an array of samples.
     * Returns the first sample or null on failure.
     */
    private CaveSample loadSingleCaveFromFile(File jsonFile) {
        try (FileReader reader = new FileReader(jsonFile)) {
            Type arrType = TypeToken.getArray(CaveSample.class).getType();
            CaveSample[] fileSamples = gson.fromJson(reader, arrType);
            if (fileSamples != null && fileSamples.length > 0) {
                return fileSamples[0];
            } else {
                getLogger().warning("No samples found in " + jsonFile.getName());
            }
        } catch (IOException e) {
            getLogger().severe("Error reading " + jsonFile.getName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Asynchronously runs the external cavegen.exe to produce realtime_cave.json,
     * then reads it and generates that cave, and finally deletes the file.
     */
    private void generateNewCaveAsync(Player player) {
        player.sendMessage("Generating a new AI cave, please wait...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            File serverRoot = Bukkit.getServer().getWorldContainer();
            File liveFile = new File(getDataFolder(), "realtime_cave.json");

            try {
                // 1) Full path to your EXE - TODO: CHANGE THIS TO YOUR REAL PATH
                File exeFile = new File(serverRoot, "AI/cavegen.exe");

                if (!exeFile.exists()) {
                    getLogger().severe("cavegen.exe not found at: " + exeFile.getAbsolutePath());
                    Bukkit.getScheduler().runTask(this, () ->
                            player.sendMessage("cavegen.exe not found. Check plugin configuration/paths."));
                    return;
                }

                // 2) Path to your weights file (change if your EXE requires it).
                // If your EXE has weights hard-coded, you can remove the --weights args entirely.
                File weightsFile = new File(serverRoot, "AI/model_1.0.pt");
                if (!weightsFile.exists()) {
                    getLogger().severe("Weights file not found at: " + weightsFile.getAbsolutePath());
                    Bukkit.getScheduler().runTask(this, () ->
                            player.sendMessage("Weights file not found. Check AI/model_1.0.pt."));
                    return;
                }

                // 3) Build the command to run the EXE
                ProcessBuilder pb = new ProcessBuilder(
                        exeFile.getAbsolutePath(),
                        "--weights", weightsFile.getAbsolutePath(),
                        "--z-dim", "64",
                        "--num-samples", "1",
                        "--out", liveFile.getAbsolutePath()
                );

                // Optional: set working directory to where the EXE / model expects
                pb.directory(exeFile.getParentFile());

                // Merge stderr into stdout
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // Log EXE output to console for debugging
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        getLogger().info("[cavegen.exe] " + line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    getLogger().severe("cavegen.exe exited with code " + exitCode);
                    Bukkit.getScheduler().runTask(this, () ->
                            player.sendMessage("Failed to generate cave (EXE error). Check server console."));
                    return;
                }

                if (!liveFile.exists()) {
                    Bukkit.getScheduler().runTask(this, () ->
                            player.sendMessage("Failed to generate cave: realtime_cave.json was not created."));
                    return;
                }

                CaveSample liveSample = loadSingleCaveFromFile(liveFile);
                if (liveSample == null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            player.sendMessage("Failed to read generated cave JSON."));
                    return;
                }

                // 4) Actually carve the cave in the world (main thread)
                Bukkit.getScheduler().runTask(this, () -> {
                    generateCaveAroundPlayer(player, liveSample);
                    player.sendMessage("New AI cave generated!");

                    // 5) Delete the realtime JSON file after use
                    if (liveFile.exists() && !liveFile.delete()) {
                        getLogger().warning("Could not delete " + liveFile.getAbsolutePath());
                    }
                });

            } catch (Exception e) {
                getLogger().severe("Error while generating new AI cave: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () ->
                        player.sendMessage("An error occurred while generating the cave. Check server console."));
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cavegen")) return false;

        // /cavegen reload
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("cavegen.reload")) {
                sender.sendMessage("You don't have permission to reload caves.");
                return true;
            }

            int count = loadCaves();
            if (count == 0) {
                sender.sendMessage("Reloaded caves, but no samples were found. Check your JSON files.");
            } else {
                sender.sendMessage("Reloaded " + count + " cave samples.");
            }
            return true;
        }

        // /cavegen new
        if (args.length > 0 && args[0].equalsIgnoreCase("new")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players may use this command.");
                return true;
            }

            generateNewCaveAsync(player);
            return true;
        }

        // /cavegen (random from pregenerated list)
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players may use this command.");
            return true;
        }

        if (samples.isEmpty()) {
            sender.sendMessage("No pregenerated cave samples loaded. Use /cavegen reload or add JSON files.");
            return true;
        }

        CaveSample sample = samples.get(random.nextInt(samples.size()));
        generateCaveAroundPlayer(player, sample);

        sender.sendMessage("Cave generated!");
        return true;
    }

    private void generateCaveAroundPlayer(Player player, CaveSample sample) {
        Location base = player.getLocation().getBlock().getLocation();
        // Roughly center a 32x32x32 cave
        base.add(-16, -8, -16);

        Bukkit.getScheduler().runTask(this, () -> {
            for (Voxel v : sample.voxels) {
                Location loc = base.clone().add(v.x, v.y, v.z);

                if (loc.getY() < player.getWorld().getMinHeight()
                        || loc.getY() > player.getWorld().getMaxHeight()) {
                    continue;
                }

                loc.getBlock().setType(Material.AIR);
            }
        });
    }
}
