import grails.util.GrailsUtil as GU
import org.codehaus.groovy.grails.commons.GrailsApplication as GA

// The targets in this script assume that Init has already been loaded.
// By not explicitly including Init here, we can use this script from
// the Events script.

// This construct makes a 'gwtForceCompile' option available to scripts
// that use these targets. We only define the property if it hasn't
// already been defined. We cannot simply initialise it here because
// all targets appear to trigger the Events script, which might then
// include this script, which would then result in the property value
// being overwritten.
//
// The events mechanism is a source of great frustration!
if (!(getBinding().variables.containsKey("gwtForceCompile"))) {
    gwtForceCompile = false
}

// We do the same for 'gwtModuleList'.
if (!(getBinding().variables.containsKey("gwtModuleList"))) {
    gwtModuleList = null
}

// Common properties and closures (used as re-usable functions).
gwtHome = Ant.antProject.properties."env.GWT_HOME"
gwtOutputPath = "${basedir}/web-app/gwt"
gwtOutputStyle = System.getProperty("gwt.output.style","OBF").toUpperCase()
srcDir = "src/gwt"
grailsSrcDir = "src/java"

/**
 * A target to check for existence of the GWT Home
 */
target(checkGwtHome: "Stops if GWT_HOME does not exist") {
    if (!gwtHome) {
        event("StatusFinal", ["GWT must be installed and GWT_HOME environment must be set."])
        exit(1)
    }
}

//
// A target for compiling any GWT modules defined in the project.
//
// Options:
//
//   gwtForceCompile - Set to true to force module compilation. Otherwise
//                     the modules are only compiled if the environment is
//                     production or the 'nocache.js' file is missing.
//
//   gwtModuleList - A collection or array of modules that should be compiled.
//                   If this is null or empty, all the modules in the
//                   application will be compiled.
//
target(compileGwtModules: "Compiles any GWT modules in 'src/gwt'.") {
    depends(checkVersion, checkGwtHome)

    // This triggers the Events scripts in the application and plugins.
    event("GwtCompileStart", ["Starting to compile the GWT modules."])

    // Compile any GWT modules. This requires the GWT 'dev' JAR file,
    // so the user must have defined the GWT_HOME environment variable
    // so that we can locate that JAR.
    def modules = gwtModuleList ?: findModules("${basedir}/${srcDir}", true)
    event("StatusUpdate", ["Compiling GWT modules"])

    modules.each {moduleName ->
        // Only run the compiler if this is production mode or
        // the 'nocache' file is older than any files in the module directory.
        if (!gwtForceCompile &&
                GU.environment != GA.ENV_PRODUCTION &&
                new File("${gwtOutputPath}/${moduleName}/${moduleName}.nocache.js").exists()) {
            // We can skip this module.
            return
        }

        event("StatusUpdate", ["Module: ${moduleName}"])
        gwtRun("com.google.gwt.dev.GWTCompiler") {
            jvmarg(value: '-Djava.awt.headless=true')
            arg(value: '-style')
            arg(value: gwtOutputStyle)
            arg(value: "-out")
            arg(value: gwtOutputPath)
            arg(value: moduleName)
        }
    }
    event("StatusUpdate", ["Finished compiling GWT modules"])

    event("GwtCompileEnd", ["Finished compiling the GWT modules."])

    event("GwtCompileEnd", [ "Finished compiling the GWT modules." ])
}

target(compileI18n: "Compiles any i18n properties files for any GWT modules in 'src/gwt'.") {
    depends(checkVersion, checkGwtHome)

    // This triggers the Events scripts in the application and plugins.
    event("GwtCompileI18nStart", ["Starting to compile the i18n properties files."])

    // Compile any i18n properties files that match the filename
    // "<Module>Constants.properties".
    def modules = gwtModuleList ?: findModules("${basedir}/${srcDir}", false)
    modules += gwtModuleList ?: findModules("${basedir}/${grailsSrcDir}", false)

    event("StatusUpdate", ["Compiling GWT i18n properties files"])

    modules.each {moduleName ->
        event("StatusUpdate", ["Module: ${moduleName}"])

        // Split the module name into package and name parts. The
        // package part includes the trailing '.'.
        def pkg = ""
        def pos = moduleName.lastIndexOf('.')
        if (pos > -1) {
            pkg = moduleName[0..pos]
            moduleName = moduleName[(pos + 1)..-1]
        }

        // Check whether the corresponding properties file exists.
        def i18nName = "${pkg}client.${moduleName}Constants"
        def i18nPath = new File(srcDir, i18nName.replace('.' as char, '/' as char) + ".properties")

        if (!i18nPath.exists()) {
            event("StatusFinal", ["No i18n file found"])
        }
        else {
            gwtRun("com.google.gwt.i18n.tools.I18NSync") {
                arg(value: "-out")
                arg(value: srcDir)
                arg(value: i18nName)
            }

            event("StatusUpdate", ["Created class ${i18nName}"])
        }
    }

    event("GwtCompileI18nEnd", ["Finished compiling the i18n properties files."])
}

target(gwtClean: "Cleans the files generated by GWT.") {
    // Start by removing the directory containing all the javascript
    // files.
    Ant.delete(dir: gwtOutputPath)

    // Now remove any generated i18n files.
    def modules = gwtModuleList ?: findModules("${basedir}/${srcDir}", false)
    modules += gwtModuleList ?: findModules("${basedir}/${grailsSrcDir}", false)

    modules.each {moduleName ->
        // Split the module name into package and name parts. The
        // package part includes the trailing '.'.
        def pkg = ""
        def pos = moduleName.lastIndexOf('.')
        if (pos > -1) {
            pkg = moduleName[0..pos]
            moduleName = moduleName[(pos + 1)..-1]
        }

        // Delete the corresponding constants file. If it doesn't
        // exist, that doesn't matter: nothing will happen.
        def pkgPath = pkg.replace('.' as char, '/' as char)
        def i18nPath = "${basedir}/${srcDir}/${pkgPath}client/${moduleName}Constants.java"
        Ant.delete(file: i18nPath)
    }
}

gwtRun = {String className, Closure body ->
    Ant.java(classname: className, fork: "true") {
        jvmarg(value: "-Xmx256m")
        // Have to prefix this with 'Ant' because the Init
        // script includes a 'classpath' target.
        Ant.classpath {
            fileset(dir: "${gwtHome}") {
                include(name: "gwt-dev*.jar")
                include(name: "gwt-user.jar")
            }

            // Include a GWT-specific lib directory if it exists.
            if (new File("${basedir}/lib/gwt").exists()) {
                fileset(dir: "${basedir}/lib/gwt") {
                    include(name: "*.jar")
                }
            }
            //must include src/java and src/gwt in classpath so that the source files can be translated
            if (new File("${basedir}/${srcDir}").exists()) {
                pathElement(location: "${basedir}/${srcDir}")
            }
            pathElement(location: "${basedir}/${grailsSrcDir}")
        }

        body.delegate = delegate
        body()
    }
}

/**
 * Searches a given directory for any GWT module files, and
 * returns a list of their fully-qualified names.
 * @param searchDir A string path specifying the directory
 * to search in.
 * @param entryPointOnly Whether to find modules that contains entry-points (ie. GWT clients)
 * @return a list of fully-qualified module names.
 */
def findModules(searchDir, entryPointOnly) {
    def modules = []
    def baseLength = searchDir.size()

    def searchDirFile = new File(searchDir)
    if (searchDirFile.exists()) {
    new File(searchDir).eachFileRecurse {file ->
        // Chop off the search directory.
        def filePath = file.path.substring(baseLength + 1)

        // Now check whether this path matches a module file.
        def m = filePath =~ /([\w\/]+)\.gwt\.xml$/
        if (m.count > 0) {
            // now check if this module has an entry point
            // if there's no entry point, then it's not necessary to compile the module
            if (!entryPointOnly || file.text =~ /entry-point/) {
                // Extract the fully-qualified module name.
                modules << m[0][1].replace(File.separatorChar, '.' as char)
            }
        }
    }
    }

    return modules
}
