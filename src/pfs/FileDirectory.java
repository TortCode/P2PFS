package pfs;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FileDirectory {
    public static class FileEntry {
        public final String fileName;
        public final String keyword;

        public FileEntry(String fileName, String keyword) {
            this.fileName = fileName;
            this.keyword = keyword;
        }
    }

    private final Path root;
    private final ConcurrentMap<String, FileEntry> fileNameMap;
    private final ConcurrentMap<String, FileEntry> keywordMap;

    public FileDirectory(String directory) {
        this.root = Path.of(directory);
        this.fileNameMap = new ConcurrentHashMap<>();
        this.keywordMap = new ConcurrentHashMap<>();
        try (DirectoryStream<Path> pathStream = Files.newDirectoryStream(this.root)) {
            for (Path path : pathStream) {
                String fileName = path.getFileName().toString();
                String keyword;
                try (BufferedReader br = Files.newBufferedReader(path)) {
                    keyword = br.readLine();
                }
                FileEntry entry = new FileEntry(fileName, keyword);
                this.fileNameMap.put(fileName, entry);
                this.keywordMap.put(keyword, entry);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createFile(String fileName, String keyword) throws IOException {
        FileEntry entry = new FileEntry(fileName, keyword);
        this.fileNameMap.put(fileName, entry);
        this.keywordMap.put(keyword, entry);
        Path path = root.resolve(fileName);
        Files.createFile(path);
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write(keyword);
            bw.newLine();
        }
    }

    public OutputStream newFileOutput(String fileName) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(this.root.resolve(fileName), StandardOpenOption.APPEND));
        return out;
    }

    public InputStream newFileInput(String fileName) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(this.root.resolve(fileName), StandardOpenOption.READ));
        while (in.read() != '\n') ;
        return in;
    }
}
