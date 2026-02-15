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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskFlowTutorial {

    public static void main(String[] args) {
        String workflowXmlResourcePath = "xml/task1.xml";
        ValidationMode validationMode = ValidationMode.FAIL_FAST;

        Workflow workflow = WorkflowLoader.loadFromResources(workflowXmlResourcePath, validationMode);
        workflow.run();
    }

    public enum ValidationMode {
        NONE,      // 不驗證
        WARN_ONLY, // 驗證但不丟例外，只印出錯誤
        FAIL_FAST  // 驗證失敗就丟例外
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
            InputStream inputStream = TaskFlowTutorial.class.getClassLoader().getResourceAsStream(resourcePath);
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

        public void run() {
            log("[TaskFlow] Run workflow: " + id);

            String currentTaskId = firstTaskId;

            while (currentTaskId != null) {
                TaskDefinition currentTask = taskByIdMap.get(currentTaskId);
                if (currentTask == null) {
                    throw new IllegalStateException("Task not found: " + currentTaskId);
                }

                String nextTaskId = execute(currentTask);
                currentTaskId = nextTaskId;
            }

            log("[TaskFlow] Workflow finished: " + id);
        }

        private String execute(TaskDefinition taskDefinition) {
            switch (taskDefinition.type) {

                case "setVar":
                    requireNonBlank(taskDefinition.key, "setVar requires key, taskId=" + taskDefinition.id);
                    variables.put(taskDefinition.key, taskDefinition.value == null ? "" : taskDefinition.value);
                    return getNextSequentialTaskId(taskDefinition.id);

                case "log":
                    String message = taskDefinition.message == null ? "" : taskDefinition.message;
                    log("[TaskFlow] " + interpolate(message));
                    return getNextSequentialTaskId(taskDefinition.id);

                case "branch":
                    requireNonBlank(taskDefinition.ifEqualsKey, "branch requires ifEqualsKey, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.thenGo, "branch requires thenGo, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.elseGo, "branch requires elseGo, taskId=" + taskDefinition.id);

                    String actualValue = variables.get(taskDefinition.ifEqualsKey);
                    boolean isMatch = actualValue != null && actualValue.equals(taskDefinition.ifEqualsValue);

                    return isMatch ? taskDefinition.thenGo : taskDefinition.elseGo;

                case "end":
                    return null;

                default:
                    throw new IllegalArgumentException("Unknown task type: " + taskDefinition.type + ", taskId=" + taskDefinition.id);
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

        private TaskDefinition(
            String id,
            String type,
            String key,
            String value,
            String message,
            String ifEqualsKey,
            String ifEqualsValue,
            String thenGo,
            String elseGo
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

            return new TaskDefinition(
                id,
                type,
                key,
                value,
                message,
                ifEqualsKey,
                ifEqualsValue,
                thenGo,
                elseGo
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
