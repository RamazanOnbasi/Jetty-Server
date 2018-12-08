import java.awt.*;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;

public class Sunucu extends AbstractHandler
{
    private String location;
    private ConcurrentLinkedQueue<NodeCache> queue_cache;
    private ConcurrentLinkedQueue<NodeCache> queue_io;
    private ConcurrentLinkedQueue<NodeScale> queue_scale;
    private ConcurrentLinkedQueue<NodeWrite> queue_w;
    private ConcurrentHashMap<String, BufferedImage> cache_m;
    private Set<String> cache_d;
    private ExecutorService executor_cache;
    private ExecutorService executor_io;
    private ExecutorService executor_scale;

    public Sunucu()
    {
        cache_m = new ConcurrentHashMap<>();
        cache_d = ConcurrentHashMap.newKeySet();
        location = System.getProperty("user.home") + "/Pictures/";
        queue_cache = new ConcurrentLinkedQueue<>();
        queue_io = new ConcurrentLinkedQueue<>();
        queue_scale = new ConcurrentLinkedQueue<>();
        queue_w = new ConcurrentLinkedQueue<>();
        executor_cache = Executors.newFixedThreadPool(100);
        executor_io = Executors.newFixedThreadPool(10);
        executor_scale = Executors.newFixedThreadPool(100);
    }

    public Color stringToColor(String colorString)
    {
        Color color;
        if(colorString.toLowerCase().equals("gray"))
            color = Color.gray;

        else{
            try {
                Field field = Class.forName("java.awt.Color").getField(colorString);
                color = (Color)field.get(null);
            } catch (Exception e) {
                color = null;
            }
        }
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),200);
    }



    @Override
    public void handle( String target,
                        Request baseRequest,
                        HttpServletRequest request,
                        HttpServletResponse response ) throws IOException, ServletException
    {
        String directory = "http://bihap.com/img/";
        //String directory = "C:\\Users\\Ramazan\\small_set";
        String fileName = request.getPathInfo().substring(1, request.getPathInfo().lastIndexOf("."));
        String fileExtension = request.getPathInfo().substring(request.getPathInfo().lastIndexOf(".")+1);
        String fileNameWithExtension = request.getPathInfo().substring(1);
        String path = directory + fileName + "." + fileExtension;
        //String path = directory+request.getPathInfo();
        int height = 0;
        int width = 0;
        Color color = null;

        response.setHeader("Content-Type", "image/"+fileExtension);

        if((request.getParameter("width") != null && request.getParameter("height") != null) || request.getParameter("color") != null)
        {
            if(request.getParameter("width") != null && request.getParameter("height") != null && request.getParameter("color") != null)
            {
                width = Integer.parseInt(request.getParameter("width"));
                height = Integer.parseInt(request.getParameter("height"));
                color = stringToColor(request.getParameter("color"));
            }

            else if(request.getParameter("color") != null)
            {
                color = stringToColor(request.getParameter("color"));
            }

            else
            {
                width = Integer.parseInt(request.getParameter("width"));
                height = Integer.parseInt(request.getParameter("height"));
            }
        }

        queue_cache.add(new NodeCache(path, fileName, fileExtension, fileNameWithExtension, width, height, color));
        executor_cache.submit(new Cache());


        try {
            synchronized (queue_w){
                queue_w.wait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
/*
        System.out.println("Cache: "+ queue_cache.size());
        System.out.println("W: "+queue_w.size());
        System.out.println("Scale: "+queue_scale.size());
        System.out.println("IO: "+queue_io.size());
*/
        NodeWrite write = queue_w.poll();
        ImageIO.write(write.getImage(), write.getFileExtension(), response.getOutputStream());
    }

    private class Cache implements Runnable{
        public void run(){
            while (!queue_cache.isEmpty())
            {
                NodeCache temp = queue_cache.poll();
                String fileNameWithExtension = temp.getFileNameWithExtension();
                BufferedImage temporary_i = null;

                if(cache_m.get(fileNameWithExtension) != null || cache_d.contains(fileNameWithExtension))
                {
                    if(cache_m.get(fileNameWithExtension) != null)
                        temporary_i = cache_m.get(fileNameWithExtension);

                    else{
                        try {
                            temporary_i = ImageIO.read(new URL(location + fileNameWithExtension));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    queue_scale.add(new NodeScale(temporary_i, temp.getFileExtension(), temp.getWidth(), temp.getHeight(), temp.getColor()));

                    executor_scale.submit(new Scale());


                }
                else
                {
                    queue_io.add(temp);
                    executor_io.submit(new IO());
                }
            }
        }
    }

    private class IO implements Runnable{
        public void run(){
            while (!queue_io.isEmpty()){
                NodeCache temp = queue_io.poll();

                String path = temp.getPath();
                String fileName = temp.getFileName();
                String fileExtension = temp.getFileExtension();
                String fileNameWithExtension = temp.getFileNameWithExtension();
                BufferedImage temporary_i = null;

                if(Runtime.getRuntime().maxMemory() -((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))>=524288000)
                {
                    try {
                        if((temporary_i = ImageIO.read(new URL(path)))!=null)
                        {
                            cache_m.put(fileName+fileExtension, temporary_i);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                else
                {
                    try {
                        if((temporary_i = ImageIO.read(new URL(path)))!=null)
                        {
                            File temporary_f = new File(location+fileNameWithExtension);

                            try {
                                ImageIO.write(temporary_i, fileExtension, temporary_f);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            cache_d.add(fileNameWithExtension);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (temporary_i != null)
                {
                    queue_scale.add(new NodeScale(temporary_i, temp.getFileExtension(), temp.getWidth(), temp.getHeight(), temp.getColor()));
                    executor_scale.submit(new Scale());
                }
            }
        }
    }
    private class Scale implements Runnable{
        public void run(){
            while (!queue_scale.isEmpty()){
                NodeScale temp = queue_scale.poll();

                BufferedImage image = temp.getImage();

                if((temp.getWidth()!=0 && temp.getHeight()!=0) || temp.getColor()!=null)
                {
                    int width;
                    int height;
                    Color color = temp.getColor();

                    if (temp.getWidth()!=0 && temp.getHeight()!=0)
                    {
                        width = temp.getWidth();
                        height = temp.getHeight();
                    }

                    else
                    {
                        width = image.getWidth();
                        height = image.getHeight();
                    }

                    BufferedImage scaledBI = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = scaledBI.createGraphics();
                    g.drawImage(image,0,0,width,height,null);
                    if(color != null)
                    {
                        g.setColor(color);
                        g.fillRect(0, 0, width, height);
                    }
                    g.dispose();
                    queue_w.add(new NodeWrite(scaledBI, temp.getFileExtension()));
                }
                else
                    queue_w.add(new NodeWrite(image, temp.getFileExtension()));

                synchronized (queue_w){
                    queue_w.notify();
                }
            }

            //System.out.println("Cache: "+ queue_cache.size());

            //System.out.println("Scale: "+queue_scale.size());
            //System.out.println("IO: "+queue_io.size());

        }
    }

    private class NodeCache{
        private String path;
        private String fileName;
        private String fileExtension;
        private String fileNameWithExtension;
        private int width;
        private int height;
        private Color color;

        public NodeCache(String path, String fileName, String fileExtension, String fileNameWithExtension, int width, int height, Color color) {
            this.path = path;
            this.fileName = fileName;
            this.fileExtension = fileExtension;
            this.fileNameWithExtension = fileNameWithExtension;
            this.height = height;
            this.width = width;
            this.color = color;
        }

        public String getPath() {
            return path;
        }

        public String getFileName() {
            return fileName;
        }

        public String getFileExtension() {
            return fileExtension;
        }

        public String getFileNameWithExtension() {
            return fileNameWithExtension;
        }

        public int getHeight() {
            return height;
        }

        public int getWidth() {
            return width;
        }

        public Color getColor() {
            return color;
        }
    }

    private class NodeScale{
        private BufferedImage image;
        private String  fileExtension;
        private int width;
        private int height;
        private Color color;

        public NodeScale(BufferedImage image, String fileExtension, int width, int height, Color color) {
            this.image = image;
            this.fileExtension = fileExtension;
            this.height = height;
            this.width = width;
            this.color = color;
        }

        public BufferedImage getImage() {
            return image;
        }

        public String getFileExtension(){
            return fileExtension;
        }

        public int getHeight() {
            return height;
        }

        public int getWidth() {
            return width;
        }

        public Color getColor() {
            return color;
        }
    }

    private class NodeWrite{
        private BufferedImage image;
        private String fileExtension;

        public NodeWrite(BufferedImage image, String fileExtension) {
            this.image = image;
            this.fileExtension = fileExtension;
        }

        public BufferedImage getImage() {
            return image;
        }

        public String getFileExtension(){
            return fileExtension;
        }
    }

    public static void main( String[] args ) throws Exception
    {
        Server server = new Server(8080);

        ContextHandler context = new ContextHandler();
        context.setContextPath("/img");
        context.setHandler(new Sunucu());

        server.setHandler(context);
        server.start();
        server.join();
    }
}