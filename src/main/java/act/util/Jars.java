package act.util;

import act.conf.ConfLoader;
import org.osgl.$;
import org.osgl.Osgl;
import org.osgl.exception.NotAppliedException;
import org.osgl.logging.L;
import org.osgl.logging.Logger;
import org.osgl.util.*;

import java.io.*;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Provides utilities manipulating jar files
 */
public enum Jars {
    ;

    private static Logger logger = L.get(Jars.class);

    public static Map<String, byte[]> buildClassNameIndex(File dir) {
        return buildClassNameIndex(dir, $.F.FALSE);
    }

    public static Map<String, byte[]> buildClassNameIndex(File dir, final $.Function<String, Boolean> ignoredClassNames) {
        final Map<String, byte[]> idx = C.newMap();
        F.JarEntryVisitor visitor = F.classNameIndexBuilder(idx, ignoredClassNames);
        scanDir(dir, visitor);
        return idx;
    }

    public static Map<String, byte[]> buildClassNameIndex(List<File> jars) {
        return buildClassNameIndex(jars, $.F.FALSE);
    }

    public static Map<String, byte[]> buildClassNameIndex(List<File> jars, final $.Func1<String, Boolean> ignoredClassNames) {
        final Map<String, byte[]> idx = C.newMap();
        F.JarEntryVisitor visitor = F.classNameIndexBuilder(idx, ignoredClassNames);
        scanList(jars, visitor);
        return idx;
    }

    /**
     * If the class is loaded from a Jar file, then return that file. Otherwise
     * return {@code null}
     */
    public static File probeJarFile(Class<?> clazz) {
        String fileName = ClassNames.classNameToClassFileName(clazz.getName());
        URL url = clazz.getClassLoader().getResource(fileName);
        if (null != url && "jar".equals(url.getProtocol())) {
            String path = url.getPath();
            String file = S.str(path).afterFirst("file:").beforeFirst(".jar!").append(".jar").toString();
            return new File(file);
        } else {
            return null;
        }
    }

    public static void scan(File file, F.JarEntryVisitor... visitors) {
        file = file.getAbsoluteFile();
        if (file.isDirectory()) {
            scanDir(file, visitors);
        } else {
            try {
                scanFile(file, visitors);
            } catch (IOException e) {
                logger.warn(e, "Error scanning jar file: %s", file.getName());
            }
        }
    }

    private static void scanList(List<File> jars, F.JarEntryVisitor visitor) {
        for (int i = 0, j = jars.size(); i < j; ++i) {
            File jar = jars.get(i);
            try {
                scanFile(jar, visitor);
            } catch (IOException e) {
                logger.warn(e, "Error scanning jar file: %s", jar.getName());
            }
        }
    }

    private static void scanDir(File jarDir, F.JarEntryVisitor... visitors) {
        File[] jars = jarDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        int n = jars.length;
        for (int i = 0; i < n; ++i) {
            File file = jars[i];
            try {
                scanFile(file, visitors);
            } catch (IOException e) {
                logger.warn(e, "Error scanning jar file: %s", file.getName());
            }
        }
    }

    private static void scanFile(File file, F.JarEntryVisitor... visitors) throws IOException {
        try {
            JarFile jar = new JarFile(file);
            try {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    for (F.JarEntryVisitor visitor : visitors) {
                        if (name.endsWith(visitor.suffixRequired())) {
                            visitor.apply(jar, entry);
                        }
                    }
                }
            } finally {
                jar.close();
            }
        } catch (IOException e) {
            logger.error(e, "error scan file: %s", file.getAbsolutePath());
        }
    }

    private static byte[] getBytes(JarFile jar, JarEntry entry) throws IOException {
        InputStream is = jar.getInputStream(entry);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IO.copy(is, baos);
        return baos.toByteArray();
    }

    private static String readContent(JarFile jar, JarEntry entry) throws IOException {
        InputStream is = jar.getInputStream(entry);
        return IO.readContentAsString(is);
    }

    public enum F {
        ;

        public static abstract class JarEntryVisitor extends $.F2<JarFile, JarEntry, Void> {
            public String suffixRequired() {
                return ".class";
            }
        }

        public static JarEntryVisitor classNameIndexBuilder(final Map<String, byte[]> map, final $.Function<String, Boolean> ignoredClassNames) {
            return new F.JarEntryVisitor() {
                @Override
                public Void apply(JarFile jarFile, JarEntry entry) throws NotAppliedException, $.Break {
                    try {
                        String className = ClassNames.classFileNameToClassName(entry.getName());
                        if (!ignoredClassNames.apply(className)) {
                            map.put(className, getBytes(jarFile, entry));
                        }
                    } catch (IOException e) {
                        throw E.ioException(e);
                    }
                    return null;
                }
            };
        }

        /**
         * Visit properties files in Jar file and add the file content to map indexed by env tag.
         * For example, a jar entry named "conf/dev/abc.properties", the content will be loaded into a properties
         * instance and then put into an existing properties indexed by "dev" tag. If no env tag found
         * then the properties will be loaded into a properties instance indexed by "common"
         * @param map the map stores the properties mapped to a configure tag (e.g. common, dev, uat etc)
         * @return the visitor
         */
        public static JarEntryVisitor appConfigFileIndexBuilder(final Map<String, Properties> map) {
            return new F.JarEntryVisitor() {
                @Override
                public String suffixRequired() {
                    return ".properties";
                }

                @Override
                public Void apply(JarFile jarFile, JarEntry jarEntry) throws NotAppliedException, Osgl.Break {
                    try {
                        String fileName = jarEntry.getName();
                        if (fileName.startsWith("conf/")) {
                            FastStr fs = FastStr.of(fileName).afterFirst('/');
                            String env = ConfLoader.common();
                            if (fs.contains('/')) {
                                env = fs.beforeFirst('/').intern();
                            }
                            Properties p = map.get(env);
                            if (null == p) {
                                p = new Properties();
                                map.put(env, p);
                            }
                            InputStream is = jarFile.getInputStream(jarEntry);
                            Properties p2 = new Properties();
                            p2.load(is);
                            p.putAll(p2);
                        }
                    } catch (IOException e) {
                        logger.warn(e, "Unable to load properties file from jar entry %s", jarEntry.getName());
                    }
                    return null;
                }
            };
        }
    }

}
