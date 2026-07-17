/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Tomcat 的启动入口类，是整个 Tomcat 服务器的引导程序。
 *
 * <h3>一、Bootstrap 的职责</h3>
 * <p>
 * Bootstrap 是 Tomcat 启动的最外层壳，它的核心任务是：
 * <ol>
 *   <li><strong>确定 catalina.home 和 catalina.base 目录</strong>：定位 Tomcat 的安装目录和工作目录</li>
 *   <li><strong>创建三层 ClassLoader</strong>：构建 common、catalina、shared 三个类加载器，
 *       将 Tomcat 内部类与应用类隔离</li>
 *   <li><strong>加载并初始化 Catalina 类</strong>：通过反射加载 org.apache.catalina.startup.Catalina，
 *       并将 sharedLoader 注入进去</li>
 *   <li><strong>转发命令</strong>：将 start/stop/configtest 等命令通过反射转发给 Catalina 实例执行</li>
 * </ol>
 * </p>
 *
 * <h3>二、为什么不直接 new Catalina()？</h3>
 * <p>
 * Bootstrap 故意不直接依赖 Catalina 类，而是通过反射加载，目的是：
 * <ul>
 *   <li><strong>类加载隔离</strong>：Catalina 内部类通过 catalinaLoader 加载，
 *       与系统 ClassPath 隔离，避免应用类看到 Tomcat 内部实现</li>
 *   <li><strong>支持多实例</strong>：同一个 Tomcat 安装（catalina.home）可以运行多个实例
 *       （catalina.base），每个实例有独立的配置和日志</li>
 *   <li><strong>支持守护进程模式</strong>：通过 daemon 机制，Bootstrap 可以作为 Windows 服务
 *       或 Unix daemon 运行</li>
 * </ul>
 * </p>
 *
 * <h3>三、三层 ClassLoader 架构</h3>
 * <pre>
 *   Bootstrap(ClassLoader=JVM System)
 *         │
 *         ▼
 *    commonLoader ──────────┐
 *         │                 │
 *         ▼                 ▼
 *   catalinaLoader      sharedLoader
 *         │                 │
 *         ▼                 ▼
 *    Catalina类         Web应用类
 * </pre>
 * <ul>
 *   <li><strong>commonLoader</strong>：公共类加载器，加载 $CATALINA_HOME/lib 下的公共类</li>
 *   <li><strong>catalinaLoader</strong>：Catalina 类加载器，加载 Tomcat 内部实现类</li>
 *   <li><strong>sharedLoader</strong>：共享类加载器，所有 Web 应用可见的共享类</li>
 * </ul>
 *
 * <h3>四、启动流程概览</h3>
 * <p>
 * 执行 <code>startup.sh</code> 或 <code>java org.apache.catalina.startup.Bootstrap start</code> 时：
 * </p>
 * <pre>
 * main()
 *   │
 *   ├─ bootstrap.init()        // 初始化 ClassLoader，反射加载 Catalina
 *   │     ├─ initClassLoaders()
 *   │     ├─ load Catalina class via catalinaLoader
 *   │     └─ setParentClassLoader(sharedLoader)
 *   │
 *   ├─ daemon.load(args)       // 反射调用 Catalina.load()，解析 server.xml
 *   │
 *   └─ daemon.start()          // 反射调用 Catalina.start()，启动服务器
 * </pre>
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @see Catalina
 * @see Constants
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * 用于 main 方法的同步锁，保证 Bootstrap 实例的初始化和命令执行是线程安全的。
     * <p>
     * 当 Tomcat 作为守护进程或服务运行时，main 方法可能被不同线程多次调用
     * （如先 start 后 stop），使用此锁保证 daemon 实例的可见性和原子性。
     * </p>
     */
    private static final Object daemonLock = new Object();

    /**
     * 单例 Bootstrap 实例，作为守护进程的入口对象。
     * <p>
     * 使用 volatile 修饰，确保多线程环境下的可见性。
     * 首次 main 调用时创建，后续调用（如 stop 命令）复用此实例。
     * </p>
     */
    private static volatile Bootstrap daemon = null;

    /**
     * catalina.base 目录，Tomcat 实例的工作目录。
     * <p>
     * 存放配置文件（conf/）、日志（logs/）、Web 应用（webapps/）、工作目录（work/）、临时目录（temp/）等。
     * 若未设置，默认等于 catalina.home。
     * </p>
     * <p>
     * 通过此变量可以实现"单安装多实例"部署：多个实例共享同一份 catalina.home（二进制），
     * 但各自有独立的 catalina.base（配置和数据）。
     * </p>
     */
    private static final File catalinaBaseFile;

    /**
     * catalina.home 目录，Tomcat 的安装目录。
     * <p>
     * 存放 Tomcat 二进制文件和共享库（lib/）。
     * 通常通过环境变量 CATALINA_HOME 或系统属性 catalina.home 设置。
     * </p>
     */
    private static final File catalinaHomeFile;

    /**
     * 用于解析配置文件中路径列表的正则表达式。
     * <p>
     * 匹配两种格式：
     * <ul>
     *   <li>双引号包围的路径：("...")</li>
     *   <li>非逗号字符序列：([^,]*)</li>
     * </ul>
     * 用于解析 catalina.properties 中的 common.loader、server.loader、shared.loader 等路径配置。
     * </p>
     */
    private static final Pattern PATH_PATTERN = Pattern.compile("(\"[^\"]*\")|(([^,])*)");

    /**
     * 静态初始化块：解析 catalina.home 和 catalina.base 目录。
     * <p>
     * 此块在类加载时执行，确定两个关键目录：
     * </p>
     * <h4>catalina.home 的解析顺序</h4>
     * <ol>
     *   <li>系统属性 catalina.home 显式指定</li>
     *   <li>当前目录下存在 bootstrap.jar，则取当前目录的父目录
     *       （即 Tomcat 标准布局：$CATALINA_HOME/bin/bootstrap.jar）</li>
     *   <li>兜底：使用当前工作目录（user.dir）</li>
     * </ol>
     * <h4>catalina.base 的解析顺序</h4>
     * <ol>
     *   <li>系统属性 catalina.base 显式指定</li>
     *   <li>兜底：与 catalina.home 相同（默认单实例模式）</li>
     * </ol>
     * <p>
     * 解析完成后，将结果写回系统属性，供后续组件（如 Catalina、日志系统）使用。
     * </p>
     */
    static {
        // Will always be non-null
        String userDir = System.getProperty("user.dir");

        // Home first
        String home = System.getProperty(Constants.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(Constants.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // Then base
        String base = System.getProperty(Constants.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(Constants.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Catalina 守护进程实例。
     * <p>
     * 通过反射加载的 org.apache.catalina.startup.Catalina 实例。
     * Bootstrap 所有命令（start/stop/configtest 等）都通过此实例反射调用执行。
     * </p>
     */
    private Object catalinaDaemon = null;

    /**
     * 公共类加载器。
     * <p>
     * 加载 $CATALINA_HOME/lib 下的公共类，是 catalinaLoader 和 sharedLoader 的父加载器。
     * </p>
     */
    ClassLoader commonLoader = null;

    /**
     * Catalina 类加载器。
     * <p>
     * 加载 Tomcat 内部实现类（org.apache.catalina.* 等）。
     * 父加载器为 commonLoader。
     * </p>
     */
    ClassLoader catalinaLoader = null;

    /**
     * 共享类加载器。
     * <p>
     * 加载所有 Web 应用共享的类。
     * 父加载器为 commonLoader，会被设置为 Web 应用类加载器的父加载器。
     * </p>
     */
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods


    /**
     * 初始化三个类加载器：commonLoader、catalinaLoader、sharedLoader。
     * <p>
     * 三个加载器的配置来自 catalina.properties 中的 common.loader、server.loader、shared.loader 属性。
     * 如果 commonLoader 创建失败（如配置错误），将导致 Tomcat 启动失败并退出。
     * </p>
     */
    private void initClassLoaders() {
        try {
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader = this.getClass().getClassLoader();
            }
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    /**
     * 根据名称创建类加载器。
     * <p>
     * 从 catalina.properties 读取 <code>${name}.loader</code> 属性，
     * 解析其中的路径列表（支持 ${catalina.home}、${catalina.base} 等变量），
     * 创建对应的类加载器。
     * </p>
     *
     * @param name   类加载器名称（如 "common"、"server"、"shared"）
     * @param parent 父类加载器，可为 null
     * @return 新创建的类加载器；如果配置为空则直接返回 parent
     * @throws Exception 如果创建过程中发生错误
     */
    private ClassLoader createClassLoader(String name, ClassLoader parent) throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals(""))) {
            return parent;
        }

        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                URI uri = new URI(repository);
                @SuppressWarnings("unused")
                URL url = uri.toURL();
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (IllegalArgumentException | MalformedURLException | URISyntaxException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring(0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * 替换字符串中的系统属性占位符。
     * <p>
     * 支持格式：<code>${property.name}</code>。
     * 特别处理 catalina.home 和 catalina.base 两个属性，使用 Bootstrap 内部的值，
     * 而非从 System.getProperty() 读取（因为此时系统属性可能尚未设置）。
     * </p>
     *
     * @param str 原始字符串，可能包含 ${...} 占位符
     * @return 替换后的字符串
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Constants.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Constants.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * 初始化 Bootstrap 守护进程。
     * <p>
     * 这是 Bootstrap 最核心的方法，完成以下三件事：
     * </p>
     * <ol>
     *   <li><strong>创建三层 ClassLoader</strong>：调用 {@link #initClassLoaders()} 创建
     *       commonLoader、catalinaLoader、sharedLoader</li>
     *   <li><strong>设置线程上下文类加载器</strong>：将当前线程的 ContextClassLoader 设为 catalinaLoader，
     *       确保 Catalina 类加载时使用正确的加载器</li>
     *   <li><strong>加载 Catalina 类并注入 sharedLoader</strong>：
     *       <ul>
     *         <li>通过 catalinaLoader 反射加载 org.apache.catalina.startup.Catalina</li>
     *         <li>创建 Catalina 实例</li>
     *         <li>调用 setParentClassLoader(sharedLoader) 注入共享类加载器</li>
     *       </ul>
     *   </li>
     * </ol>
     * <p>
     * 此外还执行 {@link SecurityClassLoad#securityClassLoad(ClassLoader)}，
     * 加载 Tomcat 安全相关的类。
     * </p>
     *
     * @throws Exception 如果初始化过程中发生致命错误
     */
    public void init() throws Exception {
        // 初始化类加载器
        initClassLoaders();

        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isTraceEnabled()) {
            log.trace("Loading startup class");
        }
        // 通过反射加载Catalina类
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        if (log.isTraceEnabled()) {
            log.trace("Setting startup class properties");
        }
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method = startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;
    }


    /**
     * 加载 Catalina 实例（通过反射调用 Catalina.load()）。
     * <p>
     * Catalina.load() 会解析 conf/server.xml，构建 Server/Service/Connector 等组件对象树，
     * 并调用 Server.init() 初始化整棵组件树。
     * </p>
     *
     * @param arguments 传给 Catalina.load() 的命令行参数，可为 null
     * @throws Exception 如果反射调用过程中发生错误
     */
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isTraceEnabled()) {
            log.trace("Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);
    }


    /**
     * 获取 Catalina 管理的 Server 实例（通过反射调用 Catalina.getServer()）。
     * <p>
     * 用于 configtest 命令验证配置是否成功加载。
     * </p>
     *
     * @return Server 实例
     * @throws Exception 如果反射调用过程中发生错误
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * 初始化 Bootstrap 并加载配置。
     * <p>
     * 等价于依次调用 {@link #init()} 和 {@link #load(String[])}。
     * </p>
     *
     * @param arguments 命令行参数
     * @throws Exception 如果初始化或加载过程中发生错误
     */
    public void init(String[] arguments) throws Exception {

        init();
        load(arguments);
    }


    /**
     * 启动 Tomcat 服务器（通过反射调用 Catalina.start()）。
     * <p>
     * 如果 catalinaDaemon 为 null（即未调用过 init()），会先执行 init()。
     * Catalina.start() 会启动 Server，级联启动所有子组件，并注册 shutdown hook。
     * </p>
     *
     * @throws Exception 如果启动过程中发生错误
     */
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            init();
        }

        Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * 停止 Tomcat 服务器（通过反射调用 Catalina.stop()）。
     * <p>
     * 优雅停止 Server，级联停止所有子组件，并销毁资源。
     * </p>
     *
     * @throws Exception 如果停止过程中发生错误
     */
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * 停止 Tomcat 服务器实例（通过反射调用 Catalina.stopServer()，无参数版本）。
     * <p>
     * 用于从外部停止运行中的 Tomcat 实例（如执行 shutdown.sh 时）。
     * </p>
     *
     * @throws Exception 如果停止过程中发生错误
     */
    public void stopServer() throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * 停止 Tomcat 服务器实例（通过反射调用 Catalina.stopServer()，带参数版本）。
     * <p>
     * 参数可包含配置文件路径等信息。
     * </p>
     *
     * @param arguments 传给 Catalina.stopServer() 的命令行参数
     * @throws Exception 如果停止过程中发生错误
     */
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    /**
     * 设置是否在启动后阻塞等待关闭命令（通过反射调用 Catalina.setAwait()）。
     * <p>
     * 当 await=true 时，Catalina.start() 会在启动完成后阻塞当前线程，
     * 直到收到 shutdown 命令才退出。这是 Tomcat 默认的运行模式。
     * </p>
     *
     * @param await true 表示阻塞等待，false 表示不阻塞
     * @throws Exception 如果反射调用过程中发生错误
     */
    public void setAwait(boolean await) throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }


    /**
     * 获取是否在启动后阻塞等待关闭命令（通过反射调用 Catalina.getAwait()）。
     *
     * @return true 表示会阻塞等待，false 表示不阻塞
     * @throws Exception 如果反射调用过程中发生错误
     */
    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method = catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * 销毁 Catalina 守护进程实例。
     * <p>
     * 目前是空实现（FIXME），暂未完成。
     * </p>
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Tomcat 的 main 方法入口，通过 startup.sh / startup.bat 或直接 java 命令调用。
     * <p>
     * 解析命令行参数，根据最后一个参数决定执行哪种命令。
     * </p>
     *
     * <h4>支持的命令</h4>
     * <table border="1">
     *   <tr><th>命令</th><th>说明</th></tr>
     *   <tr><td>start</td><td>启动 Tomcat 并阻塞等待（默认命令）</td></tr>
     *   <tr><td>startd</td><td>启动 Tomcat 但不阻塞等待</td></tr>
     *   <tr><td>stop</td><td>停止运行中的 Tomcat 实例</td></tr>
     *   <tr><td>stopd</td><td>停止 Tomcat（内部使用，等价于 stop）</td></tr>
     *   <tr><td>configtest</td><td>验证 server.xml 配置是否正确，不启动服务</td></tr>
     * </table>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><strong>初始化 daemon</strong>（首次调用时）：
     *       <ul>
     *         <li>创建 Bootstrap 实例</li>
     *         <li>调用 init() 初始化 ClassLoader 和 Catalina</li>
     *         <li>使用 daemonLock 保证线程安全</li>
     *       </ul>
     *   </li>
     *   <li><strong>复用 daemon</strong>（后续调用时，如 stop）：
     *       <ul>
     *         <li>设置当前线程的 ContextClassLoader 为 catalinaLoader</li>
     *       </ul>
     *   </li>
     *   <li><strong>执行命令</strong>：根据命令参数调用对应方法
     *       <ul>
     *         <li>start：setAwait(true) + load() + start()，阻塞等待</li>
     *         <li>startd：load() + start()，不阻塞</li>
     *         <li>stop / stopd：stop()</li>
     *         <li>configtest：load() 后检查 Server 是否创建成功</li>
     *       </ul>
     *   </li>
     *   <li><strong>异常处理</strong>：捕获 InvocationTargetException 并解包，记录日志后退出</li>
     * </ol>
     *
     * @param args 命令行参数，最后一个参数为命令名称
     */
    public static void main(String args[]) {

        synchronized (daemonLock) {
            if (daemon == null) {
                // Don't set daemon until init() has completed
                Bootstrap bootstrap = new Bootstrap();
                try {
                    // 1、初始化类加载器 + 加载Catalina
                    bootstrap.init();
                } catch (Throwable t) {
                    handleThrowable(t);
                    log.error("Init exception", t);
                    return;
                }
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                daemon.load(args);
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            log.error("Error running command", t);
            System.exit(1);
        }
    }


    /**
     * 获取 catalina.home 目录路径。
     * <p>
     * home 是 Tomcat 的安装目录，存放二进制文件和共享库。
     * 注意：home 和 base 可能是同一个目录（默认情况）。
     * </p>
     *
     * @return catalina.home 路径字符串
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * 获取 catalina.base 目录路径。
     * <p>
     * base 是 Tomcat 实例的工作目录，存放配置、日志、Web 应用等。
     * 若未设置，默认等于 catalina.home。
     * </p>
     *
     * @return catalina.base 路径字符串
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * 获取 catalina.home 目录的 File 对象。
     *
     * @return catalina.home 目录
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * 获取 catalina.base 目录的 File 对象。
     * <p>
     * 若未设置，返回 {@link #getCatalinaHomeFile()} 的值。
     * </p>
     *
     * @return catalina.base 目录
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start

    /**
     * 处理 Throwable 异常。
     * <p>
     * 从 ExceptionUtils 复制而来，因为启动阶段 ExceptionUtils 类还不可见。
     * </p>
     * <ul>
     *   <li>ThreadDeath：直接重新抛出</li>
     *   <li>StackOverflowError：静默忽略（应该是可恢复的）</li>
     *   <li>VirtualMachineError：直接重新抛出</li>
     *   <li>其他 Throwable：静默忽略</li>
     * </ul>
     *
     * @param t 要处理的异常
     */
    static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof StackOverflowError) {
            // Swallow silently - it should be recoverable
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }


    /**
     * 解包 InvocationTargetException 异常。
     * <p>
     * 从 ExceptionUtils 复制而来，避免对 utils 包的依赖。
     * 如果是 InvocationTargetException 且有 cause，返回 cause；否则原样返回。
     * </p>
     *
     * @param t 可能被包装的异常
     * @return 解包后的异常
     */
    static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }


    /**
     * 解析逗号分隔的路径列表字符串。
     * <p>
     * 支持用双引号包围包含逗号的路径，例如：
     * <code>/path/to/one, "/path/with,comma/in/it", /path/to/three</code>
     * </p>
     * <p>
     * 双引号必须成对出现，否则抛出 IllegalArgumentException。
     * </p>
     *
     * @param value 逗号分隔的路径字符串
     * @return 解析后的路径数组
     * @throws IllegalArgumentException 如果双引号不成对
     */
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character can only be used to quote paths. It must " +
                                "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }

        return result.toArray(new String[0]);
    }
}
