/* ========================================================================
 * PlantUML : a free UML diagram generator
 * ========================================================================
 *
 * Project Info:  http://plantuml.sourceforge.net
 *
 * This file is part of PlantUML.
 *
 * PlantUML is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlantUML distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 */
package net.sourceforge.plantuml.servlet;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.Optional;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.OptionFlags;
import net.sourceforge.plantuml.servlet.utility.IllegalRequestException;
import net.sourceforge.plantuml.servlet.utility.StreamIOHelper;

/*
 * Json servlet of the webapp.
 * This servlet takes JSON input to produces UML diagrams in any format.
 * This allows more complex inputs.
 */
@SuppressWarnings("serial")
public class JsonServlet extends HttpServlet {

    public static final String EXPECTED_MIME_TYPE = "application/json";

    public static final String JSON_FIELD_IMAGE_INDEX = "imageIndex";
    public static final String JSON_FIELD_RESPONSE_FORMAT = "responseFormat";
    public static final String JSON_FIELD_INPUT_FORMAT = "inputFormat";
    public static final String JSON_FIELD_INPUT_DATA = "data";
    public static final String JSON_FIELD_MAIN_FILE = "mainFile";
    public static final String JSON_FIELD_ARCHIVE_TYPE = "archiveType";

    private ArchiveStreamFactory asf = new ArchiveStreamFactory();
    private CompressorStreamFactory csf = new CompressorStreamFactory(true);

    static {
        OptionFlags.ALLOW_INCLUDE = false;
        if ("true".equalsIgnoreCase(System.getenv("ALLOW_PLANTUML_INCLUDE"))) {
            OptionFlags.ALLOW_INCLUDE = true;
        }
    }

    /**
     *
     * @param json
     * @param key
     * @return
     * @throws IllegalRequestException because
     */
    private String getStringFromJson(JsonObject json, String key) throws IllegalRequestException {
        JsonValue value = json.get(key);
        if (value == null) {
            throw new IllegalRequestException("In the given json the key \"" + key + "\" does not exist.");
        }
        if (!(value instanceof JsonString)) {
            throw new IllegalRequestException("In the given json the value of \"" + key + "\" is not a string.");
        }
        return ((JsonString) value).getString();
    }

    private String getStringFromJson(JsonObject json, String key, String defaultValue) throws IllegalRequestException {
        JsonValue value = json.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof JsonString)) {
            throw new IllegalRequestException("In the given json the value of \"" + key + "\" is not a string.");
        }
        return ((JsonString) value).getString();
    }

    private int getIntFromJson(JsonObject json, String key, int defaultValue) throws IllegalRequestException {
        JsonValue value = json.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof JsonNumber)) {
            throw new IllegalRequestException("In the given json the value of \"" + key + "\" is not a number.");
        }
        return ((JsonNumber) value).intValue();
    }

    /**
     * Parse the given request and check for any mistakes except in the actual data.
     *
     * @param req The request to parse
     * @return All the required information to continue with the request
     *
     * @throws IOException if any i/o problem occurs
     * @throws IllegalRequestException if anything in the request body is wrong
     */
    private RequestInformation parseRequest(HttpServletRequest req) throws IOException, IllegalRequestException {
        if (!req.getContentType().equals(EXPECTED_MIME_TYPE)) {
            throw new IllegalRequestException(
                    "Expected content type " + EXPECTED_MIME_TYPE + ". Was " + req.getContentType());
        }
        JsonReader reader = Json.createReader(req.getInputStream());
        JsonObject data;
        try {
            data = reader.readObject();
        } catch (JsonParsingException e2) {
            throw new IllegalRequestException("Could not parse the given json.", e2);
        } catch (JsonException e1) {
            throw new IOException(e1);
        }

        int index = getIntFromJson(data, JSON_FIELD_IMAGE_INDEX, 0);
        String responeFormatString = getStringFromJson(data, JSON_FIELD_RESPONSE_FORMAT, "PNG");
        String inputFormatString = getStringFromJson(data, JSON_FIELD_INPUT_FORMAT, "STRING");
        String inputData = getStringFromJson(data, JSON_FIELD_INPUT_DATA);

        FileFormat responseFormat;
        try {
            responseFormat = FileFormat.valueOf(responeFormatString);
        } catch (IllegalArgumentException e) {
            throw new IllegalRequestException("Unknown response file format.", e);
        }

        InputFormat inputFormat;
        try {
            inputFormat = InputFormat.valueOf(inputFormatString);
        } catch (IllegalArgumentException e) {
            throw new IllegalRequestException("Unknown input format.", e);
        }

        Optional<String> mainFile = Optional.empty();
        Optional<ArchiveType> archiveType = Optional.empty();
        if (inputFormat == InputFormat.ARCHIVE) {
            mainFile = Optional.of(getStringFromJson(data, JSON_FIELD_MAIN_FILE));
            String archiveTypeString = getStringFromJson(data, JSON_FIELD_ARCHIVE_TYPE);

            try {
                archiveType = Optional.of(ArchiveType.valueOf(archiveTypeString));
            } catch (IllegalArgumentException e) {
                throw new IllegalRequestException("Unknown archive type.", e);
            }
        }

        return new RequestInformation(index, responseFormat, inputFormat, inputData, mainFile, archiveType);
    }

    private void deleteDirecotry(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
         });
    }

    private void processArchiveInputStream(Path dir, ArchiveInputStream ais) throws IOException {
        ArchiveEntry entry = null;
        while ((entry = ais.getNextEntry()) != null) {
            if (!ais.canReadEntryData(entry)) {
                System.out.println("Could not extract an entry: " + entry.getName());
                continue;
            }
            Path entryPath = dir.resolve(entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectories(entryPath);
            } else {
                Files.createDirectories(entryPath.getParent());
                Files.createFile(entryPath);
                try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(entryPath))) {
                    StreamIOHelper.transferTo(ais, bos);
                }
            }
        }
    }

    private String detectCompression(InputStream is) throws IOException, IllegalRequestException {
        try {
            return CompressorStreamFactory.detect(is);
        } catch (CompressorException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                if (cause instanceof IOException) {
                    throw new IOException("Problem while detecting compression", e);
                } else {
                    throw new RuntimeException("Problem while detecting compression", e);
                }
            } else {
                throw new IllegalRequestException("Could not detect the compression of the data");
            }
        }
    }

    private String detectArchiveType(InputStream is) throws IOException, IllegalRequestException {
        try {
            return ArchiveStreamFactory.detect(is);
        } catch (ArchiveException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                if (cause instanceof IOException) {
                    throw new IOException("Problem while detecting archive type", e);
                } else {
                    throw new RuntimeException("Problem while detecting archive type", e);
                }
            } else {
                throw new IllegalRequestException("Could not detect the archive type of the data");
            }
        }
    }

    private void unpackArchive(Path dir, InputStream is, String compressionAlgo, String archivingAlgo)
            throws IOException, IllegalRequestException, CompressorException, ArchiveException {
        if (compressionAlgo != null) {
            if (compressionAlgo.isEmpty()) {
                unpackArchive(dir, is, detectCompression(is), archivingAlgo);
            } else {
                try (CompressorInputStream cis = csf.createCompressorInputStream(compressionAlgo, is)) {
                    unpackArchive(dir, is, null, archivingAlgo);
                }
            }
        } else {
            if (archivingAlgo.isEmpty()) {
                unpackArchive(dir, is, null, detectArchiveType(is));
            } else {
                try (ArchiveInputStream ais = asf.createArchiveInputStream(archivingAlgo, is)) {
                    processArchiveInputStream(dir, ais);
                }
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            RequestInformation info = parseRequest(req);

            System.out.println();
            System.out.println("----POST----");
            System.out.println("Index:" + info.getImageIndex());
            System.out.println("fileF: " + info.getResponseFormat().toString());
            System.out.println("mF: " + info.getInputFormat().toString());
            System.out.println("mainFile:" + info.getMainFile());
            System.out.println("arT:" + info.getArchiveType());
            System.out.println("dataLen:" + info.getInputData().length());

            DiagramResponse dr = new DiagramResponse(resp, info.getResponseFormat(), req);

            if (info.getInputFormat() == InputFormat.STRING) {
                dr.sendDiagram(info.getInputData(), info.getImageIndex());
                return;
            }

            Path tmpDir = Files.createTempDirectory("plantuml_server");
            byte[] inputBytes = Base64.getDecoder().decode(info.getInputData());

            System.out.println("tmpDir:" + tmpDir);
            System.out.println("byteLen:" + inputBytes.length);

            if (info.getInputFormat() == InputFormat.SINGLE_FILE) {
                Path tmpFile = Files.createTempFile(tmpDir, null, ".puml");
                Files.write(tmpFile, inputBytes);
                dr.sendDiagram(tmpFile, info.getImageIndex());
            }

            if (info.getInputFormat() == InputFormat.ARCHIVE) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(inputBytes)) {
                    unpackArchive(tmpDir, bais, info.getArchiveType().get().getCompressionAlgo(),
                            info.getArchiveType().get().getArchivingAlgo());
                    dr.sendDiagram(tmpDir.resolve(info.getMainFile().get()), info.getImageIndex());
                } catch (CompressorException | ArchiveException e) {
                    throw new RuntimeException(e);
                }
            }

            deleteDirecotry(tmpDir);

        } catch (IllegalRequestException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
    }

    /**
     * The possible input formats for this endpoint.
     */
    public enum InputFormat {
        /**
         * The data should be a String which represents a plantuml diagram
         */
        STRING,
        /**
         * The data should be a base64encoded String representing a text file containing a plantuml diagram
         */
        SINGLE_FILE,
        /**
         * The data should be a base64encoded String representing an archive.
         * In this case the archive type needs to be specified.
         * At least one of the contained files should be a text file containing a plantuml diagram.
         * If this input format is used, the main file, which plantuml should start interpreting must be specified.
         */
        ARCHIVE
    }

    public enum ArchiveType {
        AUTO_DETECT("", null),
        AUTO_DETECT_COMPRESSED("", ""),
        ZIP(ArchiveStreamFactory.ZIP, null),
        TAR(ArchiveStreamFactory.TAR, null),
        TAR_GZ(ArchiveStreamFactory.TAR, CompressorStreamFactory.GZIP);

        private String archiving;
        private String compression;

        private ArchiveType(String archivingAlgo, String compressionAlgo) {
            archiving = archivingAlgo;
            compression = compressionAlgo;
        }

        public String getArchivingAlgo() {
            return archiving;
        }

        public String getCompressionAlgo() {
            return compression;
        }
    }

    private class RequestInformation {
        private final int idx;
        private final FileFormat respF;
        private final InputFormat inpF;
        private final String data;
        private final Optional<String> mainF;
        private final Optional<ArchiveType> archiveT;

        public RequestInformation(int imageIndex, FileFormat responseFormat, InputFormat inputFormat, String inputData,
                Optional<String> mainFile, Optional<ArchiveType> archiveType) {
            this.idx = imageIndex;
            this.respF = responseFormat;
            this.inpF = inputFormat;
            this.data = inputData;
            this.mainF = mainFile;
            this.archiveT = archiveType;
        }

        public int getImageIndex() {
            return idx;
        }

        public FileFormat getResponseFormat() {
            return respF;
        }

        public InputFormat getInputFormat() {
            return inpF;
        }

        public String getInputData() {
            return data;
        }

        public Optional<String> getMainFile() {
            return mainF;
        }

        public Optional<ArchiveType> getArchiveType() {
            return archiveT;
        }
    }
}
