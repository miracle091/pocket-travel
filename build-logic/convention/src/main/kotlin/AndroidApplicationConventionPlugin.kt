import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = ProjectConventions.COMPILE_SDK
            defaultConfig {
                minSdk = ProjectConventions.MIN_SDK
                targetSdk = ProjectConventions.TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = ProjectConventions.JAVA_VERSION
                targetCompatibility = ProjectConventions.JAVA_VERSION
            }
        }
        configureKotlinJvmTarget()
        pluginManager.apply(DetektConventionPlugin::class.java)
    }
}
