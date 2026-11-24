import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        // Lint legge anche i sorgenti in build/generated/hilt/component_sources (pure quelli dei test, anche nell'analisi
        // degli androidTest), ma non dipende dai task che li riscrivono: in parallelo trovava un file appena cancellato.
        tasks.named { it.startsWith("lintAnalyze") || it.startsWith("lintVitalAnalyze") }.configureEach {
            // per nome: una collezione filtrata creerebbe tutti i task del modulo, anche quelli che falliscono se creati
            mustRunAfter(target.tasks.names.filter { it.startsWith("hiltJavaCompile") }.map(target.tasks::named))
        }
        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
