import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * detekt con la configurazione comune (config/detekt), le regole di formattazione di ktlint e il
 * baseline del modulo (detekt-baseline.xml).
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("dev.detekt")
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline.set(file("detekt-baseline.xml"))
            parallel.set(true)
        }
        dependencies {
            add("detektPlugins", libs.findLibrary("detekt-rules-ktlint-wrapper").get())
        }
    }
}
