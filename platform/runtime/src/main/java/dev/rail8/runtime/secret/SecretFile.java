package dev.rail8.runtime.secret;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The one reader of a secret given as a file: the value of a variable's file form ({@code X_FILE}), the absolute path of a
 * file of at most {@value #MAX_BYTES} bytes of UTF-8 text. One trailing {@code \n} or {@code \r\n} is dropped and nothing else
 * of the content is changed. The node's configuration reads the platform's own secret files with it, and the import of a
 * store of generation 0 the files of the variables it imports.
 *
 * @since 0.1
 */
public final class SecretFile {

    /** The most bytes a secret file may hold: enough for a PEM key or a service account's JSON. */
    public static final long MAX_BYTES = 64L * 1024;

    private SecretFile() {}

    /**
     * Why a file was not read: the variable as given, the check it failed ({@code check}), and the path once it is an absolute
     * one (null before). The message names the variable, the check and the path; never the content.
     */
    public static final class Unread extends IllegalArgumentException {
        private final String variable;
        private final String check;
        private final transient Path path;

        Unread(String variable, String check, Path path, Throwable cause) {
            super(variable + " " + check + (path == null ? "" : ": " + path), cause);
            this.variable = variable;
            this.check = check;
            this.path = path;
        }

        /** The variable as given, such as {@code RAIL8_SECRET_X_FILE}. */
        public String variable() {
            return variable;
        }

        /** The check the file failed, such as {@code names an empty file}: it holds no path and no content. */
        public String check() {
            return check;
        }
    }

    /**
     * The content of the file {@code raw} names, the value of {@code variable}, its one trailing line end dropped.
     *
     * @throws Unread the path is not absolute; the file is missing, cannot be read, is empty once the line end is dropped,
     *                holds more than {@value #MAX_BYTES} bytes or is not UTF-8 text
     */
    public static String read(String variable, String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException notAPath) {
            path = null;
        }
        if (path == null || !path.isAbsolute()) throw new Unread(variable, "must be the absolute path of a file (the value is not shown)", null, null);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(Math.toIntExact(MAX_BYTES + 1));
        } catch (NoSuchFileException missing) {
            throw new Unread(variable, "names a file that does not exist", path, missing);
        } catch (IOException | RuntimeException unreadable) {
            throw new Unread(variable, "names a file that cannot be read", path, unreadable);
        }
        try {
            if (bytes.length > MAX_BYTES) throw new Unread(variable, "names a file of more than " + MAX_BYTES + " bytes", path, null);
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\n') {
                length--;
                if (length > 0 && bytes[length - 1] == '\r') length--;
            }
            if (length == 0) throw new Unread(variable, "names an empty file", path, null);
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            } catch (CharacterCodingException notText) {
                throw new Unread(variable, "names a file that is not UTF-8 text (the content is not shown)", path, null);
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
