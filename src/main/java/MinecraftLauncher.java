import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.json.*;

public class MinecraftLauncher {
    private static final String OS_NAME = "windows";
    private static final String OS_ARCH = System.getProperty("os.arch").equals("amd64") ? "x86_64" : "x86";
    
    public static void main(String[] args) {
        String mcPath = ".minecraft";
        String versionName = "1.20.1";
        String playerName = "Player123";
        
        LaunchOptions options = new LaunchOptions(
            "java", // javaPath
            4096,   // memory
            false   // useSystemMemory
        );
        
        try {
            Process process = launchMinecraft(mcPath, versionName, playerName, options);
            System.out.println("Minecraft launched with PID: " + process.pid());
        } catch (Exception e) {
            System.err.println("Failed to launch Minecraft: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static Process launchMinecraft(String mcPath, String versionName, String playerName, LaunchOptions options) 
            throws IOException, InterruptedException, JSONException {
        // Normalize path
        if (!mcPath.endsWith(File.separator)) {
            mcPath += File.separator;
        }
        
        // Read version JSON file
        Path versionJsonPath = Paths.get(mcPath, "versions", versionName, versionName + ".json");
        JSONObject versionJson = readJsonFile(versionJsonPath);
        
        // Get main class
        String mainClass = versionJson.getString("mainClass");
        
        // Build libraries path
        String libraries = buildLibrariesPath(mcPath, versionJson);
        
        // Build game arguments
        String gameArgs = buildGameArguments(mcPath, versionName, playerName, versionJson);
        
        // Build Java command
        String javaCommand = buildJavaCommand(mcPath, versionName, mainClass, libraries, gameArgs, options);
        
        // Execute command
        System.out.println("Launching Minecraft with command: " + javaCommand);
        ProcessBuilder processBuilder = new ProcessBuilder(javaCommand.split(" "));
        processBuilder.inheritIO();
        return processBuilder.start();
    }
    
    private static String buildLibrariesPath(String mcPath, JSONObject versionJson) throws JSONException {
        List<String> paths = new ArrayList<>();
        
        // Add version jar
        paths.add(Paths.get(mcPath, "versions", versionJson.getString("id"), versionJson.getString("id") + ".jar").toString());
        
        // Add all libraries
        if (versionJson.has("libraries")) {
            JSONArray libraries = versionJson.getJSONArray("libraries");
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject lib = libraries.getJSONObject(i);
                
                // Check library rules
                if (!checkLibraryRules(lib)) {
                    continue;
                }
                
                // Get library path
                String libPath = getLibraryPath(mcPath, lib);
                if (libPath != null && !libPath.isEmpty()) {
                    paths.add(libPath);
                }
            }
        }
        
        return String.join(File.pathSeparator, paths);
    }
    
    private static boolean checkLibraryRules(JSONObject lib) throws JSONException {
        // If no rules, always include
        if (!lib.has("rules") || lib.getJSONArray("rules").length() == 0) {
            return true;
        }
        
        JSONArray rules = lib.getJSONArray("rules");
        boolean shouldInclude = true;
        
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String action = rule.getString("action");
            
            if (action.equals("allow")) {
                // If no OS specified, allow
                if (!rule.has("os")) {
                    shouldInclude = true;
                    continue;
                }
                
                // Check OS condition
                JSONObject os = rule.getJSONObject("os");
                if (os.getString("name").equals(OS_NAME)) {
                    // Check arch if specified
                    if (os.has("arch")) {
                        shouldInclude = os.getString("arch").equals(OS_ARCH);
                    } else {
                        shouldInclude = true;
                    }
                } else {
                    shouldInclude = false;
                }
            } else if (action.equals("disallow")) {
                // If no OS specified, disallow
                if (!rule.has("os")) {
                    shouldInclude = false;
                    continue;
                }
                
                // Check OS condition
                JSONObject os = rule.getJSONObject("os");
                if (os.getString("name").equals(OS_NAME)) {
                    shouldInclude = false;
                }
            }
        }
        
        return shouldInclude;
    }
    
    private static String getLibraryPath(String mcPath, JSONObject lib) throws JSONException {
        try {
            String[] parts = lib.getString("name").split(":");
            String groupPath = parts[0].replace(".", File.separator);
            String artifactId = parts[1];
            String version = parts[2];
            
            // Base path
            Path basePath = Paths.get(mcPath, "libraries", groupPath, artifactId, version);
            String baseFile = artifactId + "-" + version;
            
            // Check for natives
            if (lib.has("natives")) {
                JSONObject natives = lib.getJSONObject("natives");
                if (natives.has(OS_NAME)) {
                    String classifier = natives.getString(OS_NAME)
                        .replace("${arch}", System.getProperty("os.arch").equals("amd64") ? "64" : "32");
                    Path nativePath = basePath.resolve(baseFile + "-" + classifier + ".jar");
                    
                    if (Files.exists(nativePath)) {
                        return nativePath.toString();
                    }
                }
            }
            
            // Default to regular jar
            Path jarPath = basePath.resolve(baseFile + ".jar");
            if (Files.exists(jarPath)) {
                return jarPath.toString();
            }
            
            return "";
        } catch (Exception e) {
            System.err.println("Error getting library path: " + e.getMessage());
            return "";
        }
    }
    
    private static String buildGameArguments(String mcPath, String versionName, String playerName, JSONObject versionJson) 
            throws JSONException {
        String assetsPath = Paths.get(mcPath, "assets").toString();
        String assetsIndex = versionJson.optString("assets", "");
        
        StringBuilder args = new StringBuilder();
        
        // Handle older versions with minecraftArguments
        if (versionJson.has("minecraftArguments")) {
            args.append(versionJson.getString("minecraftArguments"));
        }
        
        // Handle newer versions with arguments.game
        if (versionJson.has("arguments")) {
            JSONObject arguments = versionJson.getJSONObject("arguments");
            if (arguments.has("game")) {
                JSONArray gameArgs = arguments.getJSONArray("game");
                for (int i = 0; i < gameArgs.length(); i++) {
                    Object arg = gameArgs.get(i);
                    if (arg instanceof String) {
                        args.append(" ").append(arg);
                    }
                }
            }
        }
        
        // Replace placeholders
        String result = args.toString()
            .replace("${auth_player_name}", playerName)
            .replace("${version_name}", versionName)
            .replace("${game_directory}", mcPath)
            .replace("${assets_root}", assetsPath)
            .replace("${assets_index_name}", assetsIndex)
            .replace("${auth_uuid}", "00000000-0000-0000-0000-000000000000")
            .replace("${auth_access_token}", "00000000000000000000000000000000")
            .replace("${user_type}", "legacy")
            .replace("${version_type}", "\"WMML 0.1.26\"");
        
        return result.trim();
    }
    
    private static String buildJavaCommand(String mcPath, String versionName, String mainClass, 
            String libraries, String gameArgs, LaunchOptions options) {
        // Memory settings
        String memorySettings = "";
        if (!options.useSystemMemory() && options.memory() > 0) {
            memorySettings = String.format("-Xmx%dM -Xms%dM ", options.memory(), options.memory());
        }
        
        // Common JVM arguments
        String commonArgs = String.join(" ", Arrays.asList(
            "-Dfile.encoding=GB18030",
            "-Dsun.stdout.encoding=GB18030",
            "-Dsun.stderr.encoding=GB18030",
            "-Djava.rmi.server.useCodebaseOnly=true",
            "-Dcom.sun.jndi.rmi.object.trustURLCodebase=false",
            "-Dcom.sun.jndi.cosnaming.object.trustURLCodebase=false",
            "-Dlog4j2.formatMsgNoLookups=true",
            String.format("-Dlog4j.configurationFile=%s", 
                Paths.get(mcPath, "versions", versionName, "log4j2.xml")),
            String.format("-Dminecraft.client.jar=%s", 
                Paths.get(mcPath, "versions", versionName, versionName + ".jar")),
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:G1NewSizePercent=20",
            "-XX:G1ReservePercent=20",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1HeapRegionSize=32m",
            "-XX:-UseAdaptiveSizePolicy",
            "-XX:-OmitStackTraceInFastThrow",
            "-XX:-DontCompileHugeMethods",
            "-Dfml.ignoreInvalidMinecraftCertificates=true",
            "-Dfml.ignorePatchDiscrepancies=true",
            "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
            String.format("-Djava.library.path=%s", 
                Paths.get(mcPath, "versions", versionName, "natives-windows-x86_64")),
            String.format("-Djna.tmpdir=%s", 
                Paths.get(mcPath, "versions", versionName, "natives-windows-x86_64")),
            String.format("-Dorg.lwjgl.system.SharedLibraryExtractPath=%s", 
                Paths.get(mcPath, "versions", versionName, "natives-windows-x86_64")),
            String.format("-Dio.netty.native.workdir=%s", 
                Paths.get(mcPath, "versions", versionName, "natives-windows-x86_64")),
            "-Dminecraft.launcher.brand=WMML",
            "-Dminecraft.launcher.version=0.1.26"
        ));
        
        // Construct full command
        return String.format("cmd /K %s %s%s -cp \"%s\" %s %s", 
            options.javaPath(), memorySettings, commonArgs, libraries, mainClass, gameArgs);
    }
    
    private static JSONObject readJsonFile(Path filePath) throws IOException, JSONException {
        String content = Files.readString(filePath);
        return new JSONObject(content);
    }
    
    record LaunchOptions(String javaPath, int memory, boolean useSystemMemory) {}
}