package com.microsoft.openai.samples.rag.ask.approaches.semantickernel;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.microsoft.openai.samples.rag.approaches.RAGApproach;
import com.microsoft.openai.samples.rag.approaches.RAGOptions;
import com.microsoft.openai.samples.rag.approaches.RAGResponse;
import com.microsoft.openai.samples.rag.proxy.CognitiveSearchProxy;
import com.microsoft.openai.samples.rag.proxy.OpenAIProxy;
import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.SKBuilders;
import com.microsoft.semantickernel.orchestration.SKContext;
import com.microsoft.semantickernel.planner.sequentialplanner.SequentialPlanner;
import com.microsoft.semantickernel.planner.sequentialplanner.SequentialPlannerRequestSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

/**
 *    Use Java Semantic Kernel framework with built-in Planner for functions orchestration.
 *    It uses a declarative style for AI orchestration through the built-in SequentialPlanner.
 *    SequentialPlanner call OpenAI to generate a plan for answering a question using available plugins: InformationFinder and RAG
 */
@Component
public class JavaSemanticKernelPlannerApproach implements RAGApproach<String, RAGResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSemanticKernelPlannerApproach.class);
    private static final String GOAL_PROMPT = """
            Take the input as a question and answer it finding any information needed
            """;
    private final CognitiveSearchProxy cognitiveSearchProxy;

    private final OpenAIProxy openAIProxy;

    private final OpenAIAsyncClient openAIAsyncClient;

    @Value("${openai.chatgpt.deployment}")
    private String gptChatDeploymentModelId;

    public JavaSemanticKernelPlannerApproach(CognitiveSearchProxy cognitiveSearchProxy, OpenAIAsyncClient openAIAsyncClient, OpenAIProxy openAIProxy) {
        this.cognitiveSearchProxy = cognitiveSearchProxy;
        this.openAIAsyncClient = openAIAsyncClient;
        this.openAIProxy = openAIProxy;
    }

    /**
     * @param question
     * @param options
     * @return
     */
    @Override
    public RAGResponse run(String question, RAGOptions options) {

        //Build semantic kernel context
        Kernel semanticKernel = buildSemanticKernel(options);

        SequentialPlanner sequentialPlanner = new SequentialPlanner(semanticKernel, new SequentialPlannerRequestSettings(
                0.7f,
                100,
                Set.of(),
                Set.of(),
                Set.of(),
                1024
        ), null);

        //STEP 1: ask Open AI to generate an execution plan for the goal contained in GOAL_PROMPT.
        var plan = Objects.requireNonNull(sequentialPlanner.createPlanAsync(GOAL_PROMPT).block());

        LOGGER.debug("Semantic kernel plan calculated is [{}]", plan.toPlanString());

        //STEP 2: execute the plan calculated by the planner using Open AI
        SKContext planContext = Objects.requireNonNull(plan.invokeAsync(question).block());

       return new RAGResponse.Builder()
                                .prompt(plan.toPlanString())
                                .answer(planContext.getResult())
                                //.sourcesAsText(planContext.getVariables().get("sources"))
                                .sourcesAsText("sources placeholders")
                                .question(question)
                                .build();

    }

    @Override
    public void runStreaming(String questionOrConversation, RAGOptions options, OutputStream outputStream) {
        throw new IllegalStateException("Streaming not supported for this approach");
    }

    /**
     *  Build semantic kernel context with AnswerQuestion semantic function and InformationFinder.Search native function.
     *  AnswerQuestion is imported from src/main/resources/semantickernel/Plugins.
     *  InformationFinder.Search is implemented in a traditional Java class method: CognitiveSearchPlugin.search
     *
     * @param options
     * @return
     */
    private Kernel buildSemanticKernel( RAGOptions options) {
        Kernel kernel = SKBuilders.kernel()
                .withDefaultAIService(SKBuilders.chatCompletion()
                        .withModelId(gptChatDeploymentModelId)
                        .withOpenAIClient(this.openAIAsyncClient)
                        .build())
                .build();

        kernel.importSkill(new CognitiveSearchPlugin(this.cognitiveSearchProxy, this.openAIProxy,options), "InformationFinder");

        kernel.importSkillFromResources(
                "semantickernel/Plugins",
                "RAG",
                "AnswerQuestion",
                null
        );

        return kernel;
    }



}
