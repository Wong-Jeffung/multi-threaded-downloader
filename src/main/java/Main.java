import java.io.IOException;
import java.util.ResourceBundle;

public class Main {
    public static void main(String[] args) throws IOException {
        ResourceBundle resource = ResourceBundle.getBundle("config");
        DownloadTask downloadTask = new DownloadTask(resource.getString("url"),resource.getString("localPath"),resource.getString("tempPath"),
                Integer.parseInt(resource.getString("threadNum")),Integer.parseInt(resource.getString("timeOut")),Integer.parseInt(resource.getString("sleepTime")));
        downloadTask.startDownload();
    }
}
