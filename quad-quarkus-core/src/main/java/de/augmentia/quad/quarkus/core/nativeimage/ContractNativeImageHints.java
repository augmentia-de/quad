package de.augmentia.quad.quarkus.core.nativeimage;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.contract.StepRecord;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-Image-Registrierung fuer die REST-Vertrags-DTOs aus {@code quad-contract}.
 *
 * <p>Vor der Extraktion des Vertrags trugen die DTOs ihre
 * {@code @RegisterForReflection}-Annotation selbst. Das contract-Modul ist aber
 * framework-neutral und darf keine Quarkus-Annotation mehr nennen - ein Enforcer
 * verbietet das. Die Registrierung wandert damit in die Quarkus-Schicht.
 *
 * <p>Warum ueberhaupt explizit: {@code ApiDtos} wird nicht nur als REST-Parameter
 * und -Rueckgabe benutzt, sondern an mindestens einer Stelle direkt mit einem
 * {@code ObjectMapper} gelesen und geschrieben. Diese manuelle Jackson-Nutzung
 * sieht der Quarkus-REST-Processor nicht, registriert die Typen also nicht
 * automatisch. Ohne diesen Eintrag liefert ein GraalVM-Image beim (De-)Serialisieren
 * dieser DTOs eine leere bzw. fehlerhafte Abbildung.
 *
 * <p>Registriert werden alle 13 verschachtelten Typen von {@link ApiDtos} plus
 * {@link StepRecord}. Bewusst Class-Literale: ein umbenanntes DTO bricht den
 * Build, statt zur Laufzeit still zu fehlen.
 */
@RegisterForReflection(
    targets = {
        ApiDtos.UiTaskRequest.class,
        ApiDtos.UiExecutionResult.class,
        ApiDtos.ValidationException.class,
        ApiDtos.AgentDefinition.class,
        ApiDtos.AgentCreateRequest.class,
        ApiDtos.WorkflowDef.class,
        ApiDtos.WorkflowCreateRequest.class,
        ApiDtos.WorkflowExecuteRequest.class,
        ApiDtos.StepView.class,
        ApiDtos.RunView.class,
        ApiDtos.MessagingInConfig.class,
        ApiDtos.MessagingOutConfig.class,
        ApiDtos.MessagingChannelConfig.class,
        StepRecord.class
    },
    methods = true)
public final class ContractNativeImageHints {

    private ContractNativeImageHints() {
    }
}