package io.hyscale.controller.invoker;

import io.hyscale.controller.plugins.BuildSpecValidatorHook;
import io.hyscale.controller.plugins.ImageValidatorHook;
import io.hyscale.controller.plugins.ServiceDirCleanUpHook;
import io.hyscale.dockerfile.gen.services.exception.DockerfileErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.hyscale.commons.component.ComponentInvoker;
import io.hyscale.commons.exception.HyscaleException;
import io.hyscale.commons.logger.WorkflowLogger;
import io.hyscale.commons.models.DockerfileEntity;
import io.hyscale.controller.activity.ControllerActivity;
import io.hyscale.controller.constants.WorkflowConstants;
import io.hyscale.controller.core.exception.ControllerErrorCodes;
import io.hyscale.controller.model.WorkflowContext;
import io.hyscale.dockerfile.gen.services.model.DockerfileGenContext;
import io.hyscale.dockerfile.gen.services.generator.DockerfileGenerator;
import io.hyscale.servicespec.commons.fields.HyscaleSpecFields;
import io.hyscale.servicespec.commons.model.service.ServiceSpec;

import javax.annotation.PostConstruct;

/**
 * This class invokes the @see {@link DockerfileGenerator} to generate the Dockerfile
 * It acts as a bridge between workflow controller and docker file generator
 * provides link between {@link WorkflowContext} and {@link DockerfileGenContext}
 * <p>
 * The registered plugins are executed as a part of component invocation
 */
@Component
public class DockerfileGeneratorComponentInvoker extends ComponentInvoker<WorkflowContext> {

    private static final Logger logger = LoggerFactory.getLogger(DockerfileGeneratorComponentInvoker.class);

    @Autowired
    private DockerfileGenerator dockerfileGenerator;

    @Autowired
    private ServiceDirCleanUpHook serviceDirCleanUpPlugin;

    @Autowired
    private BuildSpecValidatorHook buildSpecValidatorPlugin;

    @Autowired
    private ImageValidatorHook imageValidatorPlugin;

    @PostConstruct
    public void init() {
        super.addHook(imageValidatorPlugin);
        super.addHook(buildSpecValidatorPlugin);
        super.addHook(serviceDirCleanUpPlugin);
    }

    @Override
    protected void doExecute(WorkflowContext context) throws HyscaleException {
        ServiceSpec serviceSpec = context.getServiceSpec();
        if (serviceSpec == null) {
            throw new HyscaleException(ControllerErrorCodes.SERVICE_SPEC_REQUIRED);
        }
        WorkflowLogger.header(ControllerActivity.DOCKERFILE_GENERATION);
        DockerfileGenContext dockerfileContext = new DockerfileGenContext();

        dockerfileContext.setAppName(context.getAppName());
        try {
            dockerfileContext.setServiceName(serviceSpec.get(HyscaleSpecFields.name, String.class));
        } catch (HyscaleException e) {
            logger.error("Failed to get service name, error {}", e.toString());
            return;
        }
        try {
            DockerfileEntity dockerfileEntity = dockerfileGenerator.generateDockerfile(serviceSpec, dockerfileContext);
            context.addAttribute(WorkflowConstants.DOCKERFILE_ENTITY, dockerfileEntity);
            context.addAttribute(WorkflowConstants.STACK_AS_SERVICE_IMAGE,
                    dockerfileContext.isStackAsServiceImage());
            if (dockerfileEntity != null && dockerfileEntity.getDockerfile() != null) {
                context.addAttribute(WorkflowConstants.DOCKERFILE_INPUT,
                        dockerfileEntity.getDockerfile().getAbsolutePath());
            }
        } catch (HyscaleException e) {
            WorkflowLogger.error(ControllerActivity.DOCKERFILE_GENERATION_FAILED, e.getMessage());
            logger.error("Failed to generate dockerfile, error {}", e.toString());
            context.setFailed(true);
            throw e;
        }
    }

    @Override
    protected void onError(WorkflowContext context, HyscaleException he) {
        WorkflowLogger.header(ControllerActivity.ERROR);
        WorkflowLogger.error(ControllerActivity.CAUSE, he != null ?
                he.getMessage() : DockerfileErrorCodes.FAILED_TO_GENERATE_DOCKERFILE.getErrorMessage());
        context.setFailed(true);
    }

}