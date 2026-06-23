package ai.vishwakarma.labelling

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LabellingApplication

fun main(args: Array<String>) {
	runApplication<LabellingApplication>(*args)
}
