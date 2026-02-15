package idv.kuan.studio.libgdx.tool.workflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class TaskFlowInteractiveExample {

    public static void main(String[] args) {
        String workflowXmlResourcePath = "xml/task1.xml";
        ValidationMode validationMode = ValidationMode.FAIL_FAST;

        Workflow workflow = WorkflowLoader.loadFromResources(workflowXmlResourcePath, validationMode);

        ChoicePresenter choicePresenter = new ConsoleChoicePresenter();

        workflow.run(choicePresenter);
    }

    public enum ValidationMode {
        NONE,
        WARN_ONLY,
        FAIL_FAST
    }

    public interface ChoicePresenter {
        void showTwoOptions(
            String prompt,
            String optionAText,
            String optionAValue,
            String optionBText,
            String optionBValue,
            ChoiceCallback callback
        );
    }

    public interface ChoiceCallback {
        void onChosen(String chosenValue);
    }

    public static final class ConsoleChoicePresenter implements ChoicePresenter {

        private final Scanner scanner;

        public ConsoleChoicePresenter() {
            this.scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        }

        @Override
        public void showTwoOptions(
            String prompt,
            String optionAText,
            String optionAValue,
            String optionBText,
            String optionBValue,
            ChoiceCallback callback
        ) {
            System.out.println("[Choice] " + prompt);
            System.out.println("1) " + optionAText);
            System.out.println("2) " + optionBText);

            while (true) {
                System.out.print("Select 1 or 2: ");
                String input = scanner.nextLine();
                if ("1".equals(input)) {
                    callback.onChosen(optionAValue);
                    return;
                }
                if ("2".equals(input)) {
                    callback.onChosen(optionBValue);
                    return;
                }
                System.out.println("Invalid input. Please type 1 or 2.");
            }
        }
    }

    public static final class WorkflowLoader {

        private static final String XSD_RESOURCE_PATH = "workflow.xsd";
        private static volatile Schema cachedSchema;

        public static Workflow loadFromResources(String xmlResourcePath, ValidationMode validationMode) {

            if (validationMode != ValidationMode.NONE) {
                validateXmlFromResources(xmlResourcePath, validationMode);
            }

            Document document = parseXmlDocumentFromResources(xmlResourcePath);

            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new IllegalArgumentException("XML root is null: " + xmlResourcePath);
            }
            if (!"workflow".equals(rootElement.getTagName())) {
                throw new IllegalArgumentException("Root must be <workflow>, but got: <" + rootElement.getTagName() + ">");
            }

            String workflowId = getAttributeOrDefault(rootElement, "id", "unknown");

            List<TaskDefinition> taskOrderList = new ArrayList<>();
            Map<String, TaskDefinition> taskByIdMap = new LinkedHashMap<>();

            NodeList taskNodeList = rootElement.getElementsByTagName("task");
            if (taskNodeList.getLength() == 0) {
                throw new IllegalArgumentException("Workflow has no <task>: " + xmlResourcePath);
            }

            for (int index = 0; index < taskNodeList.getLength(); index++) {
                Element taskElement = (Element) taskNodeList.item(index);

                TaskDefinition taskDefinition = TaskDefinition.from(taskElement);

                if (taskByIdMap.containsKey(taskDefinition.id)) {
                    throw new IllegalArgumentException("Duplicate task id: " + taskDefinition.id);
                }

                taskOrderList.add(taskDefinition);
                taskByIdMap.put(taskDefinition.id, taskDefinition);
            }

            String firstTaskId = taskOrderList.get(0).id;
            return new Workflow(workflowId, firstTaskId, taskByIdMap, taskOrderList);
        }

        private static void validateXmlFromResources(String xmlResourcePath, ValidationMode validationMode) {
            try (InputStream xmlStream = openResourceStream(xmlResourcePath)) {

                Schema schema = getOrCreateSchemaFromResources();

                DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                documentBuilderFactory.setNamespaceAware(true);
                documentBuilderFactory.setSchema(schema);

                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                documentBuilder.parse(xmlStream);

            } catch (Exception exception) {
                if (validationMode == ValidationMode.WARN_ONLY) {
                    System.err.println("[TaskFlow] XML validate failed: " + xmlResourcePath);
                    exception.printStackTrace(System.err);
                    return;
                }
                throw new RuntimeException("TaskFlow XML validation failed: " + xmlResourcePath, exception);
            }
        }

        private static Document parseXmlDocumentFromResources(String xmlResourcePath) {
            try (InputStream xmlStream = openResourceStream(xmlResourcePath)) {

                DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                documentBuilderFactory.setNamespaceAware(true);

                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                return documentBuilder.parse(xmlStream);

            } catch (Exception exception) {
                throw new RuntimeException("XML parse failed: " + xmlResourcePath, exception);
            }
        }

        private static Schema getOrCreateSchemaFromResources() {
            Schema schema = cachedSchema;
            if (schema != null) {
                return schema;
            }

            synchronized (WorkflowLoader.class) {
                schema = cachedSchema;
                if (schema != null) {
                    return schema;
                }

                SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

                try (InputStream xsdStream = openResourceStream(XSD_RESOURCE_PATH)) {

                    Schema createdSchema = schemaFactory.newSchema(new StreamSource(xsdStream));
                    cachedSchema = createdSchema;
                    return createdSchema;

                } catch (Exception exception) {
                    throw new RuntimeException("TaskFlow XSD load failed: " + XSD_RESOURCE_PATH, exception);
                }
            }
        }

        private static InputStream openResourceStream(String resourcePath) {
            InputStream inputStream = TaskFlowInteractiveExample.class.getClassLoader().getResourceAsStream(resourcePath);
            if (inputStream == null) {
                throw new IllegalStateException("Resource not found in classpath: " + resourcePath);
            }
            return inputStream;
        }

        private static String getAttributeOrDefault(Element element, String attributeName, String defaultValue) {
            String value = element.getAttribute(attributeName);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return value;
        }
    }

    public static final class Workflow {

        private final String id;
        private final String firstTaskId;
        private final Map<String, TaskDefinition> taskByIdMap;
        private final List<TaskDefinition> taskOrderList;
        private final Map<String, String> variables;

        public Workflow(
            String id,
            String firstTaskId,
            Map<String, TaskDefinition> taskByIdMap,
            List<TaskDefinition> taskOrderList
        ) {
            this.id = id;
            this.firstTaskId = firstTaskId;
            this.taskByIdMap = taskByIdMap;
            this.taskOrderList = taskOrderList;
            this.variables = new LinkedHashMap<>();
        }

        public void run(ChoicePresenter choicePresenter) {
            log("[TaskFlow] Run workflow: " + id);

            String currentTaskId = firstTaskId;

            while (currentTaskId != null) {
                TaskDefinition currentTask = taskByIdMap.get(currentTaskId);
                if (currentTask == null) {
                    throw new IllegalStateException("Task not found: " + currentTaskId);
                }

                ExecutionResult executionResult = execute(currentTask, choicePresenter);
                if (executionResult.type == ExecutionResultType.WAIT) {
                    currentTaskId = null;
                } else {
                    currentTaskId = executionResult.nextTaskId;
                }
            }

            log("[TaskFlow] Workflow finished: " + id);
        }

        private ExecutionResult execute(TaskDefinition taskDefinition, ChoicePresenter choicePresenter) {
            switch (taskDefinition.type) {

                case "setVar": {
                    requireNonBlank(taskDefinition.key, "setVar requires key, taskId=" + taskDefinition.id);
                    variables.put(taskDefinition.key, taskDefinition.value == null ? "" : taskDefinition.value);
                    return ExecutionResult.next(getNextSequentialTaskId(taskDefinition.id));
                }

                case "log": {
                    String message = taskDefinition.message == null ? "" : taskDefinition.message;
                    log("[TaskFlow] " + interpolate(message));
                    return ExecutionResult.next(getNextSequentialTaskId(taskDefinition.id));
                }

                case "choice": {
                    requireNonBlank(taskDefinition.key, "choice requires key, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.prompt, "choice requires prompt, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionAText, "choice requires optionA, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionAValue, "choice requires valueA, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionBText, "choice requires optionB, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionBValue, "choice requires valueB, taskId=" + taskDefinition.id);

                    String nextTaskIdAfterChoice = getNextSequentialTaskId(taskDefinition.id);

                    choicePresenter.showTwoOptions(
                        interpolate(taskDefinition.prompt),
                        interpolate(taskDefinition.optionAText),
                        taskDefinition.optionAValue,
                        interpolate(taskDefinition.optionBText),
                        taskDefinition.optionBValue,
                        chosenValue -> {
                            variables.put(taskDefinition.key, chosenValue);
                        }
                    );

                    return ExecutionResult.next(nextTaskIdAfterChoice);
                }

                case "branch": {
                    requireNonBlank(taskDefinition.ifEqualsKey, "branch requires ifEqualsKey, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.thenGo, "branch requires thenGo, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.elseGo, "branch requires elseGo, taskId=" + taskDefinition.id);

                    String actualValue = variables.get(taskDefinition.ifEqualsKey);
                    boolean isMatch = actualValue != null && actualValue.equals(taskDefinition.ifEqualsValue);

                    return ExecutionResult.next(isMatch ? taskDefinition.thenGo : taskDefinition.elseGo);
                }

                case "goto": {
                    requireNonBlank(taskDefinition.go, "goto requires go, taskId=" + taskDefinition.id);
                    return ExecutionResult.next(taskDefinition.go);
                }

                case "end": {
                    return ExecutionResult.end();
                }

                default: {
                    throw new IllegalArgumentException("Unknown task type: " + taskDefinition.type + ", taskId=" + taskDefinition.id);
                }
            }
        }

        private String interpolate(String rawText) {
            String resolvedText = rawText;

            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                resolvedText = resolvedText.replace(placeholder, entry.getValue());
            }

            return resolvedText;
        }

        private String getNextSequentialTaskId(String currentTaskId) {
            for (int index = 0; index < taskOrderList.size(); index++) {
                TaskDefinition taskDefinition = taskOrderList.get(index);
                if (taskDefinition.id.equals(currentTaskId)) {
                    int nextIndex = index + 1;
                    if (nextIndex >= taskOrderList.size()) {
                        return null;
                    }
                    return taskOrderList.get(nextIndex).id;
                }
            }
            return null;
        }

        private void requireNonBlank(String value, String messageIfInvalid) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(messageIfInvalid);
            }
        }

        private void log(String message) {
            System.out.println(message);
        }
    }

    public enum ExecutionResultType {
        NEXT,
        WAIT,
        END
    }

    public static final class ExecutionResult {
        public final ExecutionResultType type;
        public final String nextTaskId;

        private ExecutionResult(ExecutionResultType type, String nextTaskId) {
            this.type = type;
            this.nextTaskId = nextTaskId;
        }

        public static ExecutionResult next(String nextTaskId) {
            return new ExecutionResult(ExecutionResultType.NEXT, nextTaskId);
        }

        public static ExecutionResult waiting() {
            return new ExecutionResult(ExecutionResultType.WAIT, null);
        }

        public static ExecutionResult end() {
            return new ExecutionResult(ExecutionResultType.END, null);
        }
    }

    public static final class TaskDefinition {

        public final String id;
        public final String type;

        public final String key;
        public final String value;

        public final String message;

        public final String ifEqualsKey;
        public final String ifEqualsValue;
        public final String thenGo;
        public final String elseGo;

        public final String prompt;
        public final String optionAText;
        public final String optionAValue;
        public final String optionBText;
        public final String optionBValue;

        public final String go;

        private TaskDefinition(
            String id,
            String type,
            String key,
            String value,
            String message,
            String ifEqualsKey,
            String ifEqualsValue,
            String thenGo,
            String elseGo,
            String prompt,
            String optionAText,
            String optionAValue,
            String optionBText,
            String optionBValue,
            String go
        ) {
            this.id = id;
            this.type = type;
            this.key = key;
            this.value = value;
            this.message = message;
            this.ifEqualsKey = ifEqualsKey;
            this.ifEqualsValue = ifEqualsValue;
            this.thenGo = thenGo;
            this.elseGo = elseGo;
            this.prompt = prompt;
            this.optionAText = optionAText;
            this.optionAValue = optionAValue;
            this.optionBText = optionBText;
            this.optionBValue = optionBValue;
            this.go = go;
        }

        public static TaskDefinition from(Element taskElement) {
            String id = taskElement.getAttribute("id");
            String type = taskElement.getAttribute("type");

            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Task id is required");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Task type is required, taskId=" + id);
            }

            String key = getOptionalAttribute(taskElement, "key");
            String value = getOptionalAttribute(taskElement, "value");
            String message = getOptionalAttribute(taskElement, "message");

            String ifEqualsKey = getOptionalAttribute(taskElement, "ifEqualsKey");
            String ifEqualsValue = getOptionalAttribute(taskElement, "ifEqualsValue");
            String thenGo = getOptionalAttribute(taskElement, "thenGo");
            String elseGo = getOptionalAttribute(taskElement, "elseGo");

            String prompt = getOptionalAttribute(taskElement, "prompt");
            String optionA = getOptionalAttribute(taskElement, "optionA");
            String valueA = getOptionalAttribute(taskElement, "valueA");
            String optionB = getOptionalAttribute(taskElement, "optionB");
            String valueB = getOptionalAttribute(taskElement, "valueB");

            String go = getOptionalAttribute(taskElement, "go");

            return new TaskDefinition(
                id,
                type,
                key,
                value,
                message,
                ifEqualsKey,
                ifEqualsValue,
                thenGo,
                elseGo,
                prompt,
                optionA,
                valueA,
                optionB,
                valueB,
                go
            );
        }

        private static String getOptionalAttribute(Element element, String attributeName) {
            String value = element.getAttribute(attributeName);
            if (value == null || value.isBlank()) {
                return null;
            }
            return value;
        }
    }
}
