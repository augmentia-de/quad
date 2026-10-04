package de.augmentia.quad.quarkus.core.nativeimage;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.contract.StepRecord;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native image registration for the REST contract DTOs from {@code quad-contract}.
 *
 * <p>Before the extraction of the contract the DTOs carried their
 * {@code @RegisterForReflection} annotation themselves. The contract module, however, is
 * framework-neutral and may no longer name a Quarkus annotation - an Enforcer
 * forbids that. The registration therefore moves into the Quarkus layer.
 *
 * <p>Why explicit in the first place: {@code ApiDtos} is used not only as a REST parameter
 * and return type but in at least one place is read and written directly with an
 * {@code ObjectMapper}. This manual Jackson usage
 * is not visible to the Quarkus REST processor, so it does not register the types
 * automatically. Without this entry a GraalVM image delivers an empty or faulty mapping
 * when (de-)serialising these DTOs.
 *
 * <p>All 13 nested types of {@link ApiDtos} plus
 * {@link StepRecord} are registered. Deliberately class literals: a renamed DTO breaks the
 * build, instead of being silently missing at runtime.
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