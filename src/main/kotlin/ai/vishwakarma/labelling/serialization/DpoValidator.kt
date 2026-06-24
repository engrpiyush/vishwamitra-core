package ai.vishwakarma.labelling.serialization

import ai.vishwakarma.labelling.domain.DpoPair
import ai.vishwakarma.labelling.domain.TurnKind
import ai.vishwakarma.labelling.domain.TurnRole
import org.springframework.stereotype.Component

/** Structural validation of a DPO preference pair. */
@Component
class DpoValidator {

    fun validate(pair: DpoPair): List<String> {
        val errors = mutableListOf<String>()

        if (pair.promptTurns.isEmpty()) {
            errors += "Prompt has no turns"
        } else {
            if (pair.promptTurns.first().role != TurnRole.USER)
                errors += "Prompt must start with a user turn"
            val last = pair.promptTurns.last()
            if (!(last.role == TurnRole.USER && last.kind == TurnKind.TEXT))
                errors += "Prompt must end with a user text turn"
            pair.promptTurns.forEachIndexed { i, t ->
                if (t.kind == TurnKind.TEXT && t.text.isBlank())
                    errors += "Prompt turn ${i + 1} text is empty"
            }
        }

        if (pair.chosenText.isBlank()) errors += "Chosen response is empty"
        if (pair.rejectedText.isBlank()) errors += "Rejected response is empty"
        if (pair.chosenText.isNotBlank() && pair.chosenText.trim() == pair.rejectedText.trim()) {
            errors += "Chosen and rejected responses are identical"
        }
        return errors
    }
}
