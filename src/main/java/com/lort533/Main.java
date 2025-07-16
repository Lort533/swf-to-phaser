package com.lort533;

import com.jpexs.decompiler.flash.*;
import com.jpexs.decompiler.flash.EventListener;
import com.jpexs.decompiler.flash.configuration.Configuration;
import com.jpexs.decompiler.flash.configuration.ConfigurationItem;
import com.jpexs.decompiler.flash.exporters.FrameExporter;
import com.jpexs.decompiler.flash.exporters.ShapeExporter;
import com.jpexs.decompiler.flash.exporters.modes.ButtonExportMode;
import com.jpexs.decompiler.flash.exporters.modes.ShapeExportMode;
import com.jpexs.decompiler.flash.exporters.modes.SpriteExportMode;
import com.jpexs.decompiler.flash.exporters.settings.ButtonExportSettings;
import com.jpexs.decompiler.flash.exporters.settings.ShapeExportSettings;
import com.jpexs.decompiler.flash.exporters.settings.SpriteExportSettings;
import com.jpexs.decompiler.flash.tags.*;
import com.jpexs.decompiler.flash.tags.base.*;
import com.jpexs.decompiler.flash.timeline.Timeline;
import com.jpexs.decompiler.flash.timeline.Timelined;
import com.jpexs.decompiler.flash.types.MATRIX;
import com.jpexs.decompiler.flash.types.RECT;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;

public class Main {
    private static class Settings {
        private final String exporterPath;
        private final int zoom;
        private final String preloadPackPath;

        public Settings(String exporterPath, int zoom, String preloadPackPath) {
            this.exporterPath = exporterPath;
            this.zoom = zoom;
            this.preloadPackPath = preloadPackPath;
        }

        public String getExporterPath() {
            return exporterPath;
        }

        public int getZoom() {
            return zoom;
        }

        public String getPreloadPackPath() {
            return preloadPackPath;
        }
    }

    private static class Asset {
        private String name;
        private boolean isAnimation = false;
        private boolean isButton = false;
        private final SmallestPoint smallestPoint;
        private int frames = 0;
        private float scaleX;
        private float scaleY;
        private final boolean flipX;
        private final boolean flipY;

        Asset(String name, SmallestPoint smallestPoint, float scaleX, float scaleY) {
            this.name = name;
            this.smallestPoint = smallestPoint;
            this.flipX = (scaleX < 0);
            this.flipY = (scaleY < 0);

            scaleX = Math.abs(scaleX);
            scaleY = Math.abs(scaleY);
            this.scaleX = (scaleX == 0) ? 1 : scaleX;
            this.scaleY = (scaleX == 0) ? 1 : scaleY;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setIsAnimation(boolean isAnimation) {
            this.isAnimation = isAnimation;
        }

        public boolean isAnimation() {
            return isAnimation;
        }

        public void setIsButton(boolean isButton) {
            this.isButton = isButton;
        }

        public boolean isButton() {
            return isButton;
        }

        public SmallestPoint getSmallestPoint() {
            return smallestPoint;
        }

        public int getFrames() {
            return frames;
        }

        public void setFrames(int frames) {
            this.frames = frames;
        }

        public float getScaleX() {
            return scaleX;
        }

        public void setScaleX(float scaleX) {
            this.scaleX = scaleX;
        }

        public float getScaleY() {
            return scaleY;
        }

        public void setScaleY(float scaleY) {
            this.scaleY = scaleY;
        }

        public boolean isXFlipped() {
            return flipX;
        }

        public boolean isYFlipped() {
            return flipY;
        }
    }

    private static class SmallestPoint {
        private final double x;
        private final double y;

        SmallestPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public SmallestPoint multiply(double amount) {
            return new SmallestPoint(x * amount, y * amount);
        }

        @Override
        public String toString() {
            return "SmallestPoint{x=\"" + x + "\", y=\"" + y + "\"}";
        }
    }

    private static class Scale {
        private final float x;
        private final float y;

        public Scale(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }
    }

    private static Scale getScale(MATRIX matrix) {
        return new Scale(
            ((matrix.hasScale) ? matrix.scaleX : 1),
            ((matrix.hasScale) ? matrix.scaleY : 1)
        );
    }

    private static String readFile(String path) throws IOException {
        Path filePath = Paths.get(path);
        Object[] jsonFileContents = new BufferedReader(new InputStreamReader(Files.newInputStream(filePath))).lines().toArray();
        String fileContent = "";

        for (Object jsonLine : jsonFileContents) {
            fileContent += jsonLine + "\n";
        }

        fileContent = fileContent.replaceFirst("\n$", "");

        return fileContent;
    }

    private static Settings settings = null;
    private static boolean getSettings() throws IOException {
        File settingsFile = new File("./settings.yml");
        if (!settingsFile.exists()) {
            // Thanks: https://stackoverflow.com/a/3862115
            Object[] settingsResourceFileContents = new BufferedReader(new InputStreamReader(Thread.currentThread().getContextClassLoader().getResourceAsStream("settings.yml"))).lines().toArray();
            String fileContent = "";

            for (Object settingsLine : settingsResourceFileContents) {
                fileContent += settingsLine + "\n";
            }

            fileContent = fileContent.replaceFirst("\n$", "");

            new DataOutputStream(Files.newOutputStream(settingsFile.toPath())).writeBytes(fileContent);

            System.out.println("Settings file didn't exist. Creating one (rerun the exporter)...");
            return false;
        }

        try (InputStream settingsStream = Files.newInputStream(settingsFile.toPath())) {
            Yaml settingsYaml = new Yaml();
            HashMap<String, Object> settingsMap = settingsYaml.load(settingsStream);

            settings = new Settings(
                (String) settingsMap.get("exporterPath"),
                (int) settingsMap.get("zoom"),
                (String) settingsMap.get("preloadPackPath")
            );
        } catch (Throwable e) {
            System.out.println("Error code 1: Couldn't load settings file. If you can't fix it, try deleting it (to let it recreate).");
            return false;
        }

        return true;
    }

    // Thanks: https://www.free-decompiler.com/flash/issues/2479-matrix-translatex-y-seems-to-be-incorrect
    private static SmallestPoint getSmallestPoint(SWF swf, PlaceObjectTypeTag placeObjectTag, PlaceObjectTypeTag parentPlaceObjectTag) {
        MATRIX matrix = (placeObjectTag.getMatrix() != null) ? placeObjectTag.getMatrix() : new MATRIX();

        double scaleX = getScale(matrix).getX();
        double scaleY = getScale(matrix).getY();

        double matrixXOffset = matrix.translateX / SWF.unitDivisor;
        double matrixYOffset = matrix.translateY / SWF.unitDivisor;

        Tag character = swf.getCharacter(placeObjectTag.getCharacterId());
        if (placeObjectTag != parentPlaceObjectTag) {
            MATRIX parentMatrix = (parentPlaceObjectTag.getMatrix() != null) ? parentPlaceObjectTag.getMatrix() : new MATRIX();

            double parentScaleX = getScale(parentMatrix).getX();
            double parentScaleY = getScale(parentMatrix).getY();

            scaleX *= parentScaleX;
            scaleY *= parentScaleY;

            parentScaleX = Math.abs(parentScaleX);
            parentScaleY = Math.abs(parentScaleY);

            double parentMatrixXOffset = (parentMatrix.translateX * parentScaleX) / SWF.unitDivisor; // * scaleX?
            double parentMatrixYOffset = (parentMatrix.translateY * parentScaleY) / SWF.unitDivisor; // * scaleY?

            // Unsure if that's a workaround or should it be actually done this way.
            if (character instanceof ButtonTag) {
                matrixXOffset *= (scaleX > 0) ? 1 : -1;
                matrixYOffset *= (scaleY > 0) ? 1 : -1;
            }

            matrixXOffset += parentMatrixXOffset;
            matrixYOffset += parentMatrixYOffset;
        }

        RECT rect;
        if (character instanceof DrawableTag) {
            rect = ((DrawableTag) character).getRect();
        } else {
            return new SmallestPoint(Double.MAX_VALUE, Double.MAX_VALUE);
        }

        double rectX = (scaleX > 0) ? rect.Xmin : rect.Xmax;
        double rectY = (scaleY > 0) ? rect.Ymin : rect.Ymax;

        double smallestX = ((rectX * scaleX) / SWF.unitDivisor) + matrixXOffset;
        double smallestY = ((rectY * scaleY) / SWF.unitDivisor) + matrixYOffset;

        return new SmallestPoint(smallestX, smallestY);
    }

    private static final ArrayList<Asset> assets = new ArrayList<>();
    private static ArrayList<Asset> getAssets(String name) {
        ArrayList<Asset> foundAssets = new ArrayList<>();

        for (Asset asset : assets) {
            if (asset.getName().equals(name)) {
                foundAssets.add(asset);
            }
        }

        return foundAssets;
    }

    private static float getZoomSwap(Asset asset) {
        float scaleX = asset.getScaleX();
        float scaleY = asset.getScaleY();

        float zoomSwap = 1;
        if (scaleX > scaleY) {
            zoomSwap = scaleX;
            scaleY = scaleY / scaleX;
        } else if (scaleY > scaleX) {
            zoomSwap = scaleY;
            scaleX = scaleX / scaleY;
        } else {
            zoomSwap = scaleX;

            scaleX = 1;
            scaleY = 1;
        }

        asset.setScaleX(scaleX);
        asset.setScaleY(scaleY);

        return zoomSwap;
    }

    private static void pullShapeData(String path, SWF swf, DefineShapeTag shapeTag, PlaceObjectTypeTag placeObjectTag) throws IOException, InterruptedException {
        Configuration.fixAntialiasConflation = new ConfigurationItem<>("", false, true);

        String name = shapeTag.getExportFileName();

        Asset asset = new Asset(
            name, getSmallestPoint(swf, placeObjectTag, placeObjectTag).multiply(settings.getZoom()),
            placeObjectTag.getMatrix().scaleX,
            placeObjectTag.getMatrix().scaleY
        );

        assets.add(asset);

        new ShapeExporter().exportShapes(
            new AbortRetryIgnoreHandler() {
                @Override
                public int handle(Throwable throwable) {
                    return 0;
                }

                @Override
                public AbortRetryIgnoreHandler getNewInstance() {
                    return null;
                }
            },
            path + name,
            swf,
            new ReadOnlyTagList(Arrays.asList(new DefineShapeTag[]{shapeTag})),
            new ShapeExportSettings(ShapeExportMode.PNG, settings.getZoom() * getZoomSwap(asset)),
            new EventListener() {
                @Override
                public void handleExportingEvent(String s, int i, int i1, Object o) {
                }

                @Override
                public void handleExportedEvent(String s, int i, int i1, Object o) {
                }

                @Override
                public void handleEvent(String s, Object o) {
                }
            },
            1
        );
    }

    private static void pullData(String path, SWF swf, int id, PlaceObjectTypeTag placeObjectTag, PlaceObjectTypeTag parentPlaceObjectTag) throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        CharacterTag tag = swf.getCharacter(id);
        if (tag instanceof ButtonTag) {
            return; // Is handled separately.
        }

        Asset asset = new Asset(
            tag.getExportFileName(), getSmallestPoint(swf, placeObjectTag, parentPlaceObjectTag).multiply(settings.getZoom()),
            placeObjectTag.getMatrix().scaleX,
            placeObjectTag.getMatrix().scaleY
        );

        assets.add(asset);

        new FrameExporter().exportSpriteFrames(
            new AbortRetryIgnoreHandler() {
                @Override
                public int handle(Throwable throwable) {
                    return 0;
                }

                @Override
                public AbortRetryIgnoreHandler getNewInstance() {
                    return null;
                }
            },
            path,
            swf,
            id,
            null,
            1,
            new SpriteExportSettings(SpriteExportMode.PNG, settings.getZoom() * getZoomSwap(asset)),
            new EventListener() {
                @Override
                public void handleExportingEvent(String s, int i, int i1, Object o) {}

                @Override
                public void handleExportedEvent(String s, int i, int i1, Object o) {}

                @Override
                public void handleEvent(String s, Object o) {}
            }
        );
    }

    private static void handleSmallerPart(String path, SWF swf, int id, PlaceObjectTypeTag placeObjectTag) throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Configuration.fixAntialiasConflation = new ConfigurationItem<>("", false, false);

        pullData(path, swf, id, placeObjectTag, placeObjectTag);
    }

    private static void handleBiggerPart(String path, SWF swf, Timeline timeline, int id, PlaceObjectTypeTag placeObjectTag) throws NoSuchFieldException, IOException, InterruptedException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Configuration.fixAntialiasConflation = new ConfigurationItem<>("", false, true);

        // Split a bigger part into PlaceObjects
        for (Tag placeObject : timeline.getFrame(0).innerTags) {
            if (
                !(placeObject instanceof PlaceObjectTag)
                    &&
                !(placeObject instanceof PlaceObject2Tag)
                    &&
                !(placeObject instanceof PlaceObject3Tag)
                    &&
                !(placeObject instanceof PlaceObject4Tag)
            ) {
                // TODO: Could pull data (see below) here for better Object breakup
                continue;
            }

            int placeObjectId = placeObject.getClass().getDeclaredField("characterId").getInt(placeObject);

            CharacterTag content = swf.getCharacter(placeObjectId);
            if (!(content instanceof Timelined)) { // Most likely mistaken as a bigger part (?)
                handleSmallerPart(path, swf, id, placeObjectTag);
                break;
            }

            // \/ That "pull data", could break the bigger part apart here instead
            pullData(path, swf, placeObjectId, (PlaceObjectTypeTag) placeObject, placeObjectTag);
        }
    }

    private static SWF extractAssets(String path, String folderPath) {
        try (FileInputStream file = new FileInputStream(path)) {
            SWF swf = new SWF(file, true);

            // Get contents of the main frame
            for (Tag tag : swf.getTimeline().getFrame(0).innerTags) {
                if (!(tag instanceof PlaceObjectTypeTag)) {
                    continue;
                }

                PlaceObjectTypeTag placeObject = (PlaceObjectTypeTag) tag;
                CharacterTag character = swf.getCharacter(placeObject.getCharacterId());

                // Get placed objects in main frame

                if (character instanceof DefineShapeTag) {
                    DefineShapeTag shape = (DefineShapeTag) character;

                    pullShapeData(folderPath, swf, shape, placeObject);
                }

                if (character instanceof DefineSpriteTag) {
                    DefineSpriteTag sprite = (DefineSpriteTag) character;

                    if (sprite.frameCount > 1) {
                        // If it contains over 1 frame, it's most likely a smaller part already, can export it as one part.

                        handleSmallerPart(folderPath, swf, sprite.getCharacterId(), (PlaceObjectTypeTag) tag);
                    } else {
                        // It's most likely huge part of the room, should split it into smaller parts before exporting.

                        handleBiggerPart(folderPath, swf, sprite.getTimeline(), sprite.getCharacterId(), (PlaceObjectTypeTag) tag);
                    }

                    // TODO: This isn't the right place to extract buttons. Where is it?
                    extractButtons(swf, folderPath, placeObject);
                }
            }

            return swf;
        } catch (SwfOpenException e) {
            System.out.println("\nError code 6: Invalid SWF file");
        } catch (IOException e) {
            System.out.println("\nError code 7: IO error. Please report a bug, including the following stacktrace:");
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.out.println("\nError code 8: Parsing interrupted. Please report a bug, including the following stacktrace:");
            e.printStackTrace();
        } catch (NoSuchFieldException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            System.out.println("\nError code 9: An unknown error occured. Please report a bug, including the following stacktrace:");
            e.printStackTrace();
        }

        return null;
    }

    private static void extractButtons(SWF swf, String folderPath, PlaceObjectTypeTag placeObject) throws IOException, InterruptedException {
        DefineSpriteTag sprite = (DefineSpriteTag) swf.getCharacter(placeObject.getCharacterId());
        for (Tag tag : sprite.getTimeline().getFrame(0).innerTags) {
            if (
                !(tag instanceof PlaceObjectTag)
                    &&
                !(tag instanceof PlaceObject2Tag)
                    &&
                !(tag instanceof PlaceObject3Tag)
                    &&
                !(tag instanceof PlaceObject4Tag)
            ) {
                continue;
            }

            PlaceObjectTypeTag childPlaceObject = (PlaceObjectTypeTag) tag;
            Tag underlyingTag = swf.getCharacter(childPlaceObject.getCharacterId());
            if (underlyingTag instanceof ButtonTag) {
                ButtonTag buttonTag = (ButtonTag) underlyingTag;

                float scaleX = getScale(childPlaceObject.getMatrix()).getX();
                float scaleY = getScale(childPlaceObject.getMatrix()).getY();
                float parentScaleX = getScale(placeObject.getMatrix()).getX();
                float parentScaleY = getScale(placeObject.getMatrix()).getY();

                Asset asset = new Asset(
                    underlyingTag.getExportFileName(), getSmallestPoint(swf, childPlaceObject, placeObject).multiply(settings.getZoom()),
                    (scaleX * parentScaleX),
                    (scaleY * parentScaleY)
                );
                asset.setIsButton(true);

                assets.add(asset);

                ArrayList<Integer> frames = new ArrayList<>();
                frames.add(0);
                frames.add(1);
                frames.add(2);

                new FrameExporter().exportButtonFrames(
                    new AbortRetryIgnoreHandler() {
                        @Override
                        public int handle(Throwable throwable) {
                            return 0;
                        }

                        @Override
                        public AbortRetryIgnoreHandler getNewInstance() {
                            return null;
                        }
                    },
                    folderPath,
                    swf,
                    buttonTag.getCharacterId(),
                    frames,
                    new ButtonExportSettings(ButtonExportMode.PNG, settings.getZoom() * getZoomSwap(asset)),
                    new EventListener() {
                        @Override
                        public void handleExportingEvent(String s, int i, int i1, Object o) {}

                        @Override
                        public void handleExportedEvent(String s, int i, int i1, Object o) {}

                        @Override
                        public void handleEvent(String s, Object o) {}
                    }
                );
            } else if (underlyingTag instanceof DefineSpriteTag) {
                extractButtons(swf, folderPath, childPlaceObject);
            }
        }
    }

    private static String getExtensionlessFileName(File file) {
        return file.getName().replaceFirst("\\..+$", "");
    }

    private static boolean renameAssets(String assetImgsPath) throws IOException {
        System.out.println("Please type a new name for each asset - must be alphanumeric, and can't start with a number.");
        System.out.println("Don't include \".png\" on the end, and do NOT change file names manually.\n");

        assetDuplicates.clear();
        for (Asset asset : assets) {
            boolean correctName = true;

            do {
                String assetName = asset.getName();

                assetDuplicates.putIfAbsent(assetName, 0);
                int duplicateCount = assetDuplicates.get(assetName) + 1;
                if (!correctName) {
                    duplicateCount--;
                    correctName = true;
                }

                if (duplicateCount > 1) {
                    continue;
                }
                assetDuplicates.put(assetName, duplicateCount);

                System.out.print(assetName + " => ");
                String newName = new BufferedReader(new InputStreamReader(System.in)).readLine();

                if (newName.isEmpty()) {
                    System.out.println("Wrong input - name can't be empty.");

                    correctName = false;
                    continue;
                }

                if ((!newName.matches("^[A-Za-z0-9_]*$")) || (String.valueOf(newName.charAt(0)).matches("[0-9]"))) {
                    System.out.println("Wrong input - name MUST be alphanumeric, and MUST not start with a number.");

                    correctName = false;
                    continue;
                }

                File newFolder = new File(assetImgsPath + newName + "/");
                if (newFolder.exists()) {
                    System.out.println("Wrong input - this name is taken.");

                    correctName = false;
                    continue;
                }

                File oldFolder = new File(assetImgsPath + assetName + "/");
                if (oldFolder.renameTo(new File(assetImgsPath + newName + "/"))) {
                    for (Asset sameNameAsset : getAssets(assetName)) {
                        sameNameAsset.setName(newName);
                    }

                    assetDuplicates.remove(assetName);
                    assetDuplicates.put(newName, duplicateCount);
                } else {
                    System.out.println("\nError code 11: An unknown error occurred when changing asset name.");
                    return false;
                }
            } while (!correctName);
        }

        return true;
    }

    private static void packPrepareAssets(File folder, String folderPath) {
        try {
            for (File definedFolder : Objects.requireNonNull(folder.listFiles())) {
                try {
                    File[] definedFiles = Objects.requireNonNull(definedFolder.listFiles());
                    String pathBeginning = folderPath + definedFolder.getName();

                    if (definedFiles.length == 1) {
                        if (definedFiles[0].renameTo(new File(pathBeginning + ".png"))) {
                            definedFolder.delete();
                        } else {
                            System.out.println("\nError code 12: This shouldn't happen!");
                        }

                        continue;
                    }

                    if (getAssets(definedFolder.getName()).get(0).isButton()) {
                        if (
                            (definedFiles[0].renameTo(new File(pathBeginning + ".png")))
                                &&
                            (definedFiles[1].renameTo(new File(pathBeginning + "-hover.png")))
                                &&
                            (definedFiles[2].renameTo(new File(pathBeginning + "-active.png")))
                        ) {
                            definedFolder.delete();
                        } else {
                            System.out.println("\nError code 13: This shouldn't happen!");
                        }

                        continue;
                    }

                    int frameCount = 0;
                    for (File definedFile : definedFiles) {
                        String order = getExtensionlessFileName(definedFile); // PNG files created by JPEXS only have a frame number as filename.
                        while (order.length() < 4) {
                            order = "0" + order;
                        }

                        int orderNumber = Integer.parseInt(order);
                        if (orderNumber > frameCount) {
                            frameCount = orderNumber;
                        }

                        definedFile.renameTo(new File(pathBeginning + "/" + definedFolder.getName() + "_" + order + ".png"));
                    }

                    ArrayList<Asset> assets = getAssets(definedFolder.getName());
                    for (Asset asset : assets) {
                        asset.setIsAnimation(true);
                        asset.setFrames(frameCount);
                    }
                } catch (NullPointerException ignored) {} // No files
            }
        } catch (NullPointerException ignored) {} // No folders
    }

    private static void packAssets(String swfFileName, String folderPath) throws IOException {
        new File(folderPath + "asset_packer_files/").mkdir();

        String[] commands = new String[3];
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            commands[0] = "cmd.exe";
            commands[1] = "/c";
        } else { // TODO: Perhaps OSes that don't have bash, if any?
            commands[0] = "/bin/sh";
            commands[1] = "-c";
        }
        commands[2] = settings.getExporterPath().replaceAll("%SWF_NAME%", swfFileName).replaceAll("%SWF_PATH%", Matcher.quoteReplacement(folderPath + "asset_imgs/"));

        ProcessBuilder processBuilder = new ProcessBuilder(commands);
        processBuilder.directory(new File(folderPath + "asset_packer_files/"));

        Process process = processBuilder.start();
        try {
            new BufferedReader(new InputStreamReader(process.getInputStream())).lines().forEach((s) -> {});
        } catch (Throwable ignored) {}
    }

    private static void postAssetPack(String swfFileName, String folderPath) throws IOException {
        Path assetPackerPath = Paths.get(folderPath + "asset_packer_files/" + swfFileName + ".json");

        String jsonFileContent = readFile(folderPath + "asset_packer_files/" + swfFileName + ".json")
            .replaceAll("\"filename\": \"(.*)\\.png\"", "\"filename\": \"$1\"");

        assetPackerPath.toFile().delete();
        new DataOutputStream(Files.newOutputStream(assetPackerPath)).writeBytes(jsonFileContent);
    }

    private static String preparePackFile(String sceneName) {
        return
            "{\n" +
            "    \"section1\": {\n" +
            "        \"files\": [\n" +
            "            {\n" +
            "                \"type\": \"multiatlas\",\n" +
            "                \"url\": \"assets/media/rooms/" + sceneName + "/" + sceneName + ".json\",\n" +
            "                \"path\": \"assets/media/rooms/" + sceneName + "/\",\n" +
            "                \"key\": \"" + sceneName + "\"\n" +
            "            }\n" +
            "        ]\n" +
            "    },\n" +
            "    \"meta\": {\n" +
            "        \"app\": \"Phaser Editor 2D - Asset Pack Editor\",\n" +
            "        \"contentType\": \"phasereditor2d.pack.core.AssetContentType\",\n" +
            "        \"url\": \"https://phasereditor2d.com\",\n" +
            "        \"version\": 2\n" +
            "    }\n" +
            "}\n";
    }

    private final static HashMap<String, Integer> assetDuplicates = new HashMap<>();
    private static String prepareSceneFile(SWF swf, String sceneName) {
        String fileContent =
            "{\n" +
            "    \"id\": \"" + UUID.randomUUID() + "\",\n" +
            "    \"sceneType\": \"SCENE\",\n" +
            "    \"settings\": {\n" +
            "        \"compilerInsertSpaces\": true,\n" +
            "        \"javaScriptInitFieldsInConstructor\": true,\n" +
            "        \"exportClass\": true,\n" +
            "        \"superClassName\": \"RoomScene\",\n" +
            "        \"preloadMethodName\": \"_preload\",\n" +
            "        \"preloadPackFiles\": [\n" +
            "            \"" + settings.getPreloadPackPath().replaceAll("%SCENE_NAME%", sceneName) + "\"\n" +
            "        ],\n" +
            "        \"createMethodName\": \"_create\",\n" +
            "        \"sceneKey\": \"" + sceneName + "\",\n" +
            "        \"borderWidth\": " + ((swf.getRect().getWidth() / SWF.unitDivisor) * settings.getZoom()) + ",\n" +
            "        \"borderHeight\": " + ((swf.getRect().getHeight() / SWF.unitDivisor) * settings.getZoom()) + "\n" +
            "    },\n" +
            "    \"displayList\": [\n";

        assetDuplicates.clear();
        for (Asset asset : assets) {
            String assetName = asset.getName();
            String labelName = assetName;
            SmallestPoint smallestPoint = asset.getSmallestPoint();

            assetDuplicates.putIfAbsent(assetName, 0);
            int duplicateCount = assetDuplicates.get(assetName) + 1;
            assetDuplicates.put(assetName, duplicateCount);
            if (duplicateCount > 1) {
                labelName += "_" + duplicateCount;
            }

            if (asset.isAnimation()) {
                fileContent +=
                    "        {\n" +
                    "            \"type\": \"Sprite\",\n" +
                    "            \"id\": \"" + UUID.randomUUID() + "\",\n" +
                    "            \"label\": \"" + labelName + "\",\n" +
                    "            \"components\": [\n" +
                    "                \"Animation\"\n" +
                    "            ],\n" +
                    "            \"Animation.key\": \"" + assetName + "/" + assetName + "\",\n" +
                    "            \"Animation.end\": " + asset.getFrames() + ",\n" +
                    "            \"texture\": {\n" +
                    "                \"key\": \"" + sceneName + "\",\n" +
                    "                \"frame\": \"" + assetName + "/" + assetName + "_0001\"\n" +
                    "            },\n" +
                    "            \"x\": " + smallestPoint.getX() + ",\n" +
                    "            \"y\": " + smallestPoint.getY();
            } else {
                fileContent +=
                    "        {\n" +
                    "            \"type\": \"Image\",\n" +
                    "            \"id\": \"" + UUID.randomUUID() + "\",\n" +
                    "            \"label\": \"" + labelName + "\",\n" +
                    "            \"components\": [";

                if (asset.isButton()) {
                    fileContent +=
                        "\n" +
                        "                \"Button\"\n" +
                        "            ],\n" +
                        "            \"Button.spriteName\": \"" + assetName + "\",\n";
                } else {
                    fileContent += "],\n";
                }

                fileContent +=
                    "            \"texture\": {\n" +
                    "                \"key\": \"" + sceneName + "\",\n" +
                    "                \"frame\": \"" + assetName + "\"\n" +
                    "            },\n" +
                    "            \"x\": " + smallestPoint.getX() + ",\n" +
                    "            \"y\": " + smallestPoint.getY();
            }

            if (asset.getScaleX() != 1) {
                fileContent += ",\n" +
                    "            \"scaleX\": " + asset.getScaleX();
            }

            if (asset.getScaleY() != 1) {
                fileContent += ",\n" +
                    "            \"scaleY\": " + asset.getScaleY();
            }

            if (asset.isXFlipped()) {
                fileContent += ",\n" +
                    "            \"flipX\": true";
            }

            if (asset.isYFlipped()) {
                fileContent += ",\n" +
                    "            \"flipY\": true";
            }

            fileContent += "\n        },\n";
        }

        fileContent = fileContent.replaceFirst(",$", "");

        fileContent +=
            "    ],\n" +
            "    \"plainObjects\": [],\n" +
            "    \"meta\": {\n" +
            "        \"app\": \"Phaser Editor 2D - Scene Editor\",\n" +
            "        \"url\": \"https://phasereditor2d.com\",\n" +
            "        \"contentType\": \"phasereditor2d.core.scene.SceneContentType\",\n" +
            "        \"version\": 3\n" +
            "    },\n" +
            "    \"lists\": []\n" +
            "}";

        return fileContent;
    }

    private static String prepareJsFile(String className, String sceneName) {
        String fileContent =
            "import RoomScene from '../RoomScene'\n" +
            "\n" +
            "import { Animation, Button, MoveTo, SimpleButton, Zone } from '@components/components'\n" +
            "\n" +
            "\n" +
            "/* START OF COMPILED CODE */\n" +
            "\n" +
            "export default class " + className + " extends RoomScene {\n" +
            "\n" +
            "    constructor() {\n" +
            "        super(\"" + className + "\");\n" +
            "\n" +
            "        /* START-USER-CTR-CODE */\n" +
            "\n" +
            "        /* END-USER-CTR-CODE */\n" +
            "    }\n" +
            "\n" +
            "    /** @returns {void} */\n" +
            "    _preload() {\n" +
            "        this.load.pack(\"" + sceneName + "-pack\", \"assets/media/rooms/" + sceneName + "/" + sceneName + "-pack.json\");\n" +
            "    }\n" +
            "\n" +
            "    /** @returns {void} */\n" +
            "    _create() {\n";

        assetDuplicates.clear();
        for (Asset asset : assets) {
            String assetName = asset.getName();
            String labelName = assetName;
            SmallestPoint smallestPoint = asset.getSmallestPoint();

            assetDuplicates.putIfAbsent(assetName, 0);
            int duplicateCount = assetDuplicates.get(assetName) + 1;
            assetDuplicates.put(assetName, duplicateCount);
            if (duplicateCount > 1) {
                labelName += "_" + duplicateCount;
            }

            if (asset.isAnimation()) {
                fileContent +=
                    "\n" +
                    "        // " + labelName + "\n" +
                    "        const " + labelName + " = this.add.sprite(" + smallestPoint.getX() + ", " + smallestPoint.getY() + ", \"" + sceneName + "\", \"" + assetName + "/" + assetName + "_0001\");\n" +
                    "        " + labelName + ".setOrigin(0, 0);\n";
            } else {
                fileContent +=
                    "\n" +
                    "        // " + labelName + "\n" +
                    "        const " + labelName + " = this.add.image(" + smallestPoint.getX() + ", " + smallestPoint.getY() + ", \"" + sceneName + "\", \"" + assetName + "\");\n" +
                    "        " + labelName + ".setOrigin(0, 0);\n";
            }

            if ((asset.getScaleX() != 1) || (asset.getScaleY() != 1)) {
                fileContent += "        " + labelName + ".setScale(" + asset.getScaleX() + ", " + asset.getScaleY() + ");\n";
            }

            if (asset.isXFlipped()) {
                fileContent += "        " + labelName + ".flipX = true;\n";
            }

            if (asset.isYFlipped()) {
                fileContent += "        " + labelName + ".flipY = true;\n";
            }
        }

        assetDuplicates.clear();
        for (Asset asset : assets) {
            String assetName = asset.getName();
            String labelName = assetName;

            assetDuplicates.putIfAbsent(assetName, 0);
            int duplicateCount = assetDuplicates.get(assetName) + 1;
            assetDuplicates.put(assetName, duplicateCount);
            if (duplicateCount > 1) {
                labelName += "_" + duplicateCount;
            }

            if (asset.isButton()) {
                fileContent +=
                    "\n" +
                    "        // " + labelName + " (components)\n" +
                    "        const " + labelName + "Button = new Button(" + labelName + ");\n" +
                    "        " + labelName + "Button.spriteName = \"" + assetName + "\";\n";
            } else if (asset.isAnimation()) {
                fileContent +=
                    "\n" +
                    "        // " + labelName + " (components)\n" +
                    "        const " + labelName + "Animation = new Animation(" + labelName + ");\n" +
                    "        " + labelName + "Animation.key = \"" + assetName + "/" + assetName + "_\";\n" +
                    "        " + labelName + "Animation.end = " + asset.getFrames() + ";\n";
            }
        }

        fileContent +=
            "\n" +
            "        this.events.emit(\"scene-awake\");\n" +
            "    }\n" +
            "\n" +
            "\n" +
            "    /* START-USER-CODE */\n" +
            "\n" +
            "    create() {\n" +
            "        super.create()\n" +
            "    }\n" +
            "\n" +
            "    /* END-USER-CODE */\n" +
            "}\n" +
            "\n" +
            "/* END OF COMPILED CODE */";

        return fileContent;
    }

    public static void main(String[] args) throws IOException {
        System.out.println("SWF to Phaser 1.1.1\n");

        // Get settings (thanks: https://www.baeldung.com/java-snake-yaml)
        if (!getSettings()) {
            return;
        }

        System.out.print("Enter path to the file: ");
        String path = new BufferedReader(new InputStreamReader(System.in)).readLine();

        // Early file and folder processing
        File swfFile = new File(path);
        String swfFileName = getExtensionlessFileName(swfFile);

        if (!swfFile.exists()) {
            System.out.println("\nError code 2: The SWF file doesn't exist.");
            return;
        }

        if (swfFile.getParentFile() == null) {
            System.out.println("\nError code 3: Could not get SWF file's parent directory.");
            return;
        }

        if (!swfFileName.matches("^[A-Za-z0-9_]*$")) {
            System.out.println("\nError code 4: SWF file name contains disallowed characters, change it to the alphanumeric format and try again.");
            return;
        }

        String folderPath = swfFile.getParentFile().getPath() + "/" + swfFileName + "/";
        File folder = new File(folderPath);

        if (!folder.mkdir()) {
            System.out.println("\nError code 5: Folder for exporting results already exists. Please remove the folder named the same way as the SWF file and try again.");

            System.out.print("\nWould you like to repack the assets? (Y/N): ");
            if (new BufferedReader(new InputStreamReader(System.in)).readLine().equalsIgnoreCase("y")) {
                System.out.print("Step 1: Re-texture packing SWF file assets...");
                packAssets(swfFileName, folderPath);
                System.out.print(" done!\n");

                System.out.print("Step 2: Modifying the texture pack...");
                postAssetPack(swfFileName, folderPath);
                System.out.print(" done!\n");

                System.out.println("\nAsset repacking has finished successfully!");
            }

            return;
        }

        // Extracting assets from the SWF file
        System.out.print("Step 1: Extracting SWF file assets...");

        String assetImgsPath = folderPath + "asset_imgs/";
        SWF swf = extractAssets(path, assetImgsPath);
        if (swf == null) {
            System.out.println("\nError code 10: Something went wrong.");
            return;
        }

        System.out.print(" done!\n");

        // Renaming the assets, if user wants to
        System.out.print("Would you like to rename the assets (it may be harder to do later)? (Y/N): ");
        if (new BufferedReader(new InputStreamReader(System.in)).readLine().equalsIgnoreCase("y")) {
            renameAssets(assetImgsPath);
        }

        // Restructuring the assets for packing
        System.out.print("Step 2: Preparing SWF file assets...");
        packPrepareAssets(new File(assetImgsPath), assetImgsPath);
        System.out.print(" done!\n");

        // Packing the assets (thanks: https://www.baeldung.com/run-shell-command-in-java, https://stackoverflow.com/a/38234016)
        System.out.print("Step 3: Texture packing SWF file assets...");
        packAssets(swfFileName, folderPath);
        System.out.print(" done!\n");

        // Fixing the asset JSON
        System.out.print("Step 4: Modifying the texture pack...");
        postAssetPack(swfFileName, folderPath);
        System.out.print(" done!\n");

        // Creating the (...)-pack.json file
        System.out.print("Step 5: Creating the (...)-pack.json file...");

        File packFile = new File(folderPath + "asset_packer_files/" + swfFileName + "-pack.json");
        if (!packFile.createNewFile()) {
            System.out.println("\nError code 14: Could not create the (...)-pack.json file!");
            return;
        }
        new DataOutputStream(Files.newOutputStream(packFile.toPath())).writeBytes(preparePackFile(swfFileName));

        System.out.print(" done!\n");

        // Creating the .scene file
        System.out.print("Step 6: Creating the .scene file...");

        String className = String.valueOf(swfFileName.charAt(0)).toUpperCase() + swfFileName.substring(1);

        File sceneFile = new File(folderPath + className + ".scene");
        if (!sceneFile.createNewFile()) {
            System.out.println("\nError code 15: Could not create the scene file!");
            return;
        }
        new DataOutputStream(Files.newOutputStream(sceneFile.toPath())).writeBytes(prepareSceneFile(swf, swfFileName));

        System.out.print(" done!\n");

        // Creating the JS file
        System.out.print("Step 7: Creating the JS file...");

        File jsFile = new File(folderPath + className + ".js");
        if (!jsFile.createNewFile()) {
            System.out.println("\nError code 16: Could not create the JS file!");
            return;
        }
        new DataOutputStream(Files.newOutputStream(jsFile.toPath())).writeBytes(prepareJsFile(className, swfFileName));

        System.out.print(" done!\n");

        // Done
        System.out.println("\nExporting has finished successfully!");
    }
}
