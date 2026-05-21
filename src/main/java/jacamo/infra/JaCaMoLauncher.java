package jacamo.infra;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

import jaca.CAgentArch;
import jacamo.platform.Cartago;
import jacamo.platform.EnvironmentWebInspector;
import jacamo.platform.Jade;
import jacamo.platform.Moise;
import jacamo.platform.Platform;
import jacamo.platform.Sai;
import jacamo.project.JaCaMoProject;
import jacamo.project.parser.JaCaMoProjectParser;
import jacamo.project.parser.ParseException;
import jacamo.util.Config;
import jason.JasonException;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Literal;
import jason.asSyntax.directives.DirectiveProcessor;
import jason.asSyntax.directives.Include;
import jason.infra.centralised.CentralisedAgArch;
import jason.infra.centralised.RunCentralisedMAS;
import jason.infra.repl.ReplAgGUI;
import jason.mas2j.AgentParameters;
import jason.mas2j.ClassParameters;
import jason.runtime.MASConsoleGUI;
import jason.runtime.MASConsoleLogHandler;
import jason.runtime.RuntimeServices;
import jason.runtime.Settings;
import jason.runtime.SourcePath;

/**
 * Runs MASProject using JaCaMo infrastructure.
 *
 * This class reads the mas2j project and create the
 * corresponding environment, organisation, agents, ...
 *
 * @author Jomi
 */
public class JaCaMoLauncher extends RunCentralisedMAS {

    protected List<Platform> platforms = new ArrayList<>();

    public static String defaultProjectFileName = "default.jcm";

    public static void main(String[] args) throws JasonException {
        logger = Logger.getLogger(JaCaMoLauncher.class.getName());
        JaCaMoLauncher r = new JaCaMoLauncher();
        runner = r;
        r.registerMBean();
        r.init(args);
        r.create();
        r.start();
        r.waitEnd();
        r.finish();
    }

    public static JaCaMoLauncher getJaCaMoRunner() {
        return (JaCaMoLauncher)runner;
    }
    public JaCaMoProject getJaCaMoProject() {
        if ( !(getProject() instanceof JaCaMoProject)) {
            // create a jacamo project based on mas2j project
            project = new JaCaMoProject(getProject());
        }
        return (JaCaMoProject)getProject();
    }

    @Override
    public RuntimeServices getRuntimeServices() {
        if (singRTS == null)
            singRTS = new JaCaMoRuntimeServices(runner);
        return singRTS;
    }
    
    @Override
    public int init(String[] args) {
        String projectFileName = resolveProjectFileName(args);
        initConfig();
        configureLogging(args);

        int errorCode = 0;
        try {
            errorCode = loadProject(projectFileName, args);
        } catch (FileNotFoundException e1) {
            logger.log(Level.SEVERE, "File " + projectFileName + " not found!");
            errorCode = 2;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error!?: ", e);
            errorCode = 4;
        }

        System.out.flush();
        System.err.flush();

        if (!MASConsoleGUI.hasConsole() && errorCode != 0) {
            System.exit(errorCode);
        }
        return errorCode;
    }

    private String resolveProjectFileName(String[] args) {
        if (args.length < 1) {
            if (RunCentralisedMAS.class.getResource("/"+defaultProjectFileName) != null) {
                readFromJAR = true;
                Config.get(false); // to void to call fix/store the configuration in this case everything is read from a jar/jnlp file
                return defaultProjectFileName;
            } else {
                System.out.println("JaCaMo "+Config.get().getJaCaMoVersion());
                System.err.println("You should inform the project file.");
                JOptionPane.showMessageDialog(null,"You should inform the project file as a parameter.\n\nJaCaMo version "+Config.get().getJaCaMoVersion(),"JaCaMo", JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
                return null;
            }
        } else {
            Config.get(); // to setup the JaCaMo Config
            return args[0];
        }
    }

    private void initConfig() {
        // jacamo.jar and moise.jar must be configured (for includes)
        if (Config.get().getJaCaMoHome().isEmpty() || Config.get().get(Config.MOISE_JAR) == null) {
            //if (Config.get().getUserConfFile().exists())
            //    System.out.println("JaCaMo is not configured, creating a default configuration.");
            //else
            Config.get().setShowFixMsgs(false);
            Config.get().fix();             
        }
    }

    private void configureLogging(String[] args) {
        setupLogger();

        if (args.length >= 2 && args[1].equals("-debug")) {
            debug = true;
            Logger.getLogger("").setLevel(Level.FINE);
        }

        // discover the handler
        for (Handler h : Logger.getLogger("").getHandlers()) {
            // if there is a MASConsoleLogHandler, show it
            if (h.getClass().toString().equals(MASConsoleLogHandler.class.toString())) {
                MASConsoleGUI.get().getFrame().setVisible(true);
                MASConsoleGUI.get().setAsDefaultOut();
            }
        }
    }

    private InputStream openProjectInputStream(String projectFileName) throws IOException {
        if (readFromJAR) {
            urlPrefix = SourcePath.CRPrefix + "/";
            return RunCentralisedMAS.class.getResource("/"+defaultProjectFileName).openStream();
        }
        URL file;
        // test if the argument is an URL
        try {
            file = new URL(projectFileName);
            if (projectFileName.startsWith("jar")) {
                urlPrefix = projectFileName.substring(0,projectFileName.indexOf("!")+1) + "/";
            }
        } catch (IOException e) {
            file = new URL("file:"+projectFileName);
        }
        return file.openStream();
    }

    private String resolveProjectDirectory(String projectFileName) {
        try {
            return new File(projectFileName).getAbsoluteFile().getParentFile().toString();
        } catch (RuntimeException e) {
            return new File(".").getAbsoluteFile().getParentFile().toString();
        }
    }

    private int loadProject(String projectFileName, String[] args) throws IOException {
        InputStream inProject = openProjectInputStream(projectFileName);
        JaCaMoProjectParser parser = new JaCaMoProjectParser(inProject);
        String directory = resolveProjectDirectory(projectFileName);

        try {
            project = parser.parse(directory);
        } catch (ParseException e) {
            if (e.toString().startsWith("jacamo.project.parser.ParseException: Encountered \"MAS\"")) {
                // it is the case of a .mas2j file
                return super.init(args);
            } else {
                logger.log(Level.SEVERE, "Error parsing file " + projectFileName + "!", e);
                return 3;
            }
        }
        project.setupDefault();
        getJaCaMoProject().setUrlPrefix(urlPrefix);
        project.registerDirectives();
        // set the aslSrcPath in the include
        ((Include)DirectiveProcessor.getDirective("include")).setSourcePath(project.getSourcePaths());

        if (MASConsoleGUI.hasConsole()) {
            MASConsoleGUI.get().setTitle("MAS Console - " + project.getSocName());
            if (!project.isJade()) {
                super.createButtons();
            }
        }
        
        // register jacamo archs
        getRuntimeServices().registerDefaultAgArch(JaCaMoAgArch.class.getName());
        getRuntimeServices().registerDefaultAgArch(CAgentArch.class.getName());
        
        return 0;
    }

    void createCustomPlatforms() {
        boolean einsp = false;
        Platform p = null;
        for (String pId: getJaCaMoProject().getCustomPlatforms()) {
            try {               
                p = (Platform)Class.forName(pId).newInstance();
                p.setJcmProject(getJaCaMoProject());
                p.init( getJaCaMoProject().getPlatformParameters(pId) );
                platforms.add(p);
                einsp = einsp || pId.equals(EnvironmentWebInspector.class.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // include our own platforms
        if (!einsp) {
            p = new EnvironmentWebInspector();
            try {
                p.init(null);
            } catch (Exception e) {
                e.printStackTrace();
            }
            platforms.add(0,p);
        }
    }
    
    @Override
    public void finish() {
        super.finish();
        
        for (Platform p: platforms) {
            try {
                p.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    @Override
    protected InputStream getDefaultLogProperties() throws IOException {
        return JaCaMoLauncher.class.getResource("/templates/" + logPropFile).openStream();
    }

    /** create environment, agents, controller */
    @Override
    public void create() throws JasonException {
        createCustomPlatforms();
        createEnvironment();
        createOrganisation();
        createInstitution();
        createAgs();
        //createController();        
    }

    @Override
    protected void start() {       
        for (Platform p: platforms) {
            try {
                p.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }        
        super.start(); // start agents after platforms 
    }

    public void createEnvironment() throws JasonException {
        Cartago p = new Cartago();
        p.setJcmProject(getJaCaMoProject());
        try {
            p.init(getJaCaMoProject().getPlatformParameters("cartago") );
            platforms.add(Math.min(platforms.size(), 2), p);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void createInstitution() {
        if (!getJaCaMoProject().getInstitutions().isEmpty()) {
            Sai p = new Sai();
            p.setJcmProject(getJaCaMoProject());
            try {
                p.init(getJaCaMoProject().getPlatformParameters("moise") );
                platforms.add(1,p);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
        

    protected void createOrganisation() {
        if (!getJaCaMoProject().getOrgs().isEmpty()) {
            Moise p = new Moise();
            p.setJcmProject(getJaCaMoProject());
            try {
                p.init(getJaCaMoProject().getPlatformParameters("moise") );
                platforms.add(1,p);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
        
    @Override
    public void createAgs() throws JasonException {
        // add jacamo archs
        List<AgentParameters> lags = new ArrayList<>();
        for (AgentParameters ap: getJaCaMoProject().getAgents()) {
            if (ap.getNbInstances() > 0 && getJaCaMoProject().isInDeployment(ap.getHost())) {
                if (getJaCaMoProject().getNodeHost(ap.getHost()) != null) {
                    logger.warning("**** Remote agent creation is not implemented yet! The agent "+ap.getAgName()+" @ "+getJaCaMoProject().getNodeHost(ap.getHost())+" wasn't created");
                    continue;
                }
                lags.add(ap);
                /*ap.insertArchClass( // so that the user ag arch is the first arch in the chain
                        new ClassParameters(CAgentArch.class.getName()),
                        new ClassParameters(JaCaMoAgArch.class.getName()));*/
    
                // includes mind inspector
                String debug = ap.getOption("debug");
                if (debug != null) {
                    try {
                        Literal lDebug = ASSyntax.parseLiteral(debug);
                        ap.addOption(lDebug.getFunctor(), lDebug.getTerm(0).toString());
                    } catch (jason.asSyntax.parser.ParseException e) {
                        logger.log(Level.WARNING, "Error installing mind inspector for agent "+ap.getAgName(),e);
                    }
                }
            }
        }
        project.getAgents().clear(); // clear only after iterating the getAgInstances!
        project.getAgents().addAll(lags);
        project.fixAgentsSrc();

        Jade jadePlat = null;
        if (getProject().isJade()) {
            jadePlat = new Jade();
            jadePlat.setJcmProject(getJaCaMoProject());
            try {
                jadePlat.init(getJaCaMoProject().getPlatformParameters("jade"));
                platforms.add(jadePlat);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            super.createAgs();
        }
    }

    @Override
    protected void startAgs() {
        if (!getProject().isJade()) {
            super.startAgs();
        }
    }

    @Override
    protected void createReplAg(String n) {
        CentralisedAgArch agArch = new CentralisedAgArch();
        try {
            agArch.setAgName(n);
            List<String> archs = new ArrayList<>();
            archs.add(CAgentArch.class.getName());
            archs.add(JaCaMoAgArch.class.getName());

            agArch.createArchs(archs, ReplAgGUI.class.getName(), null, null, new Settings(), JaCaMoLauncher.this);
            Thread agThread = new Thread(agArch);
            agArch.setThread(agThread);
            agThread.start();
        } catch (JasonException e1) {
            e1.printStackTrace();
        }
        addAg(agArch);
    }

}
