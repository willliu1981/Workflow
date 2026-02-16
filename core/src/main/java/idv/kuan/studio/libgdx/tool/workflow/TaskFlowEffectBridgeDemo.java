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
import java.util.Objects;
import java.util.Scanner;

/**
 * TaskFlowEffectBridgeDemo（第二版示範：effect bridge）
 *
 * 教學目標：
 * 1) 讓 workflow 不只做「顯示效果」（log/choice），還能觸發「實際 App 行為」
 *    例如：獲得金幣、設定旗標、授與稱號、更新 GameControl 狀態。
 *
 * 2) 示範「任務 A → 任務 B」的依賴關係：
 *    B 任務需要 A 任務的結果（例如 questA_done=true）才能進行。
 *
 * 關鍵設計：
 * - VariableStore：workflow 的狀態儲存區（變數表）
 * - EffectHandler：副作用橋接出口（真正的 App/遊戲行為由這裡執行）
 * - 共享 VariableStore：同一個 main 連續跑 A→B，就能保證 B 讀得到 A 寫入的結果
 */
public class TaskFlowEffectBridgeDemo {

    public static void main(String[] args) {
        ValidationMode validationMode = ValidationMode.FAIL_FAST;

        // choice 的呈現方式（Console / LibGDX / Android）由 Presenter 決定
        ChoicePresenter choicePresenter = new ConsoleChoicePresenter();

        // 教學重點：共享同一份 VariableStore，才能把 A 的結果「帶到」B
        // 若你改成 FileVariableStore，則可以跨 App 重啟持續保留狀態（持久化）。
        VariableStore sharedVariableStore = new MapVariableStore();

        // 教學重點：副作用橋接出口。
        // workflow 只描述「要做什麼 effect」，真正執行由 EffectHandler 決定。
        // 未來你直接把 DemoGameEffectHandler 換成呼叫 GameControl / GameController 即可。
        EffectHandler effectHandler = new DemoGameEffectHandler();

        // 1) 跑任務 A：在 XML 中可能會 setVar / effect，寫入 questA_done / gold / 旗標等
        Workflow workflowA = WorkflowLoader.loadFromResources("xml/taskA.xml", validationMode);
        workflowA.run(choicePresenter, effectHandler, sharedVariableStore);

        // 顯示一下目前狀態（教學用）
        System.out.println();
        System.out.println("==================================================");
        System.out.println("[Debug] After TaskA variables snapshot:");
        printSnapshot(sharedVariableStore);
        System.out.println("==================================================");
        System.out.println();

        // 2) 跑任務 B：依賴 A 寫入的狀態，例如：
        // <task type="branch" ifEqualsKey="questA_done" ifEqualsValue="true" .../>
        Workflow workflowB = WorkflowLoader.loadFromResources("xml/taskB.xml", validationMode);
        workflowB.run(choicePresenter, effectHandler, sharedVariableStore);

        System.out.println();
        System.out.println("==================================================");
        System.out.println("[Debug] After TaskB variables snapshot:");
        printSnapshot(sharedVariableStore);
        System.out.println("==================================================");
    }

    /**
     * 教學用：把 VariableStore 的狀態印出來
     */
    private static void printSnapshot(VariableStore variableStore) {
        Map<String, String> snapshot = variableStore.snapshot();
        if (snapshot.isEmpty()) {
            System.out.println("(empty)");
            return;
        }
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            System.out.println(entry.getKey() + "=" + entry.getValue());
        }
    }

    /**
     * ValidationMode 用於控制 XSD 驗證策略：
     * - NONE：不驗證（最快，但可能跑到一半才爆）
     * - WARN_ONLY：驗證錯誤只印警告（適合開發期快速迭代）
     * - FAIL_FAST：驗證錯誤直接中止（適合發佈或嚴格資料品質）
     */
    public enum ValidationMode {
        NONE,
        WARN_ONLY,
        FAIL_FAST
    }

    /**
     * ChoicePresenter：把「選擇題 UI」抽象化
     * Console 只是其中一種實作；未來可用 LibGDX/Android UI 取代。
     */
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

    /**
     * ChoiceCallback：用來回傳使用者選擇結果（把 value 寫入 VariableStore）
     */
    public interface ChoiceCallback {
        void onChosen(String chosenValue);
    }

    /**
     * ConsoleChoicePresenter：Console 互動版
     * - 顯示兩個選項
     * - 輸入 1/2 決定結果
     */
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

    /**
     * VariableStore：workflow 執行時的 state（變數表）
     *
     * 教學重點：
     * - workflow A、B 共用同一份 VariableStore → 才能做跨 workflow 的依賴測試
     * - 若要跨程式重啟共享 → 改成 FileVariableStore / DBVariableStore
     */
    public interface VariableStore {
        String get(String key);
        void put(String key, String value);
        Map<String, String> snapshot();
    }

    /**
     * MapVariableStore：最小實作（純記憶體，不持久化）
     */
    public static final class MapVariableStore implements VariableStore {

        private final Map<String, String> variables;

        public MapVariableStore() {
            this.variables = new LinkedHashMap<>();
        }

        @Override
        public String get(String key) {
            return variables.get(key);
        }

        @Override
        public void put(String key, String value) {
            if (key == null || key.isBlank()) {
                return;
            }
            variables.put(key, value == null ? "" : value);
        }

        @Override
        public Map<String, String> snapshot() {
            return new LinkedHashMap<>(variables);
        }
    }

    /**
     * EffectHandler：副作用橋接出口（最重要的接軌點）
     *
     * 觀念：
     * - DSL 只描述「觸發什麼 effect + 參數」
     * - 真正行為由 EffectHandler 執行（例如加金幣、更新 GameControl、送獎勵）
     *
     * 好處：
     * - workflow 引擎維持純資料流程（可重用）
     * - App/遊戲決定 effect 的實作（可替換）
     */
    public interface EffectHandler {
        void handle(String effectName, Map<String, String> parameters, VariableStore variableStore);
    }

    /**
     * DemoGameEffectHandler：示範版 effect 實作
     *
     * 你未來要接 App：
     * - 把這裡的 switch case 改成呼叫 GameControl / GameController
     * - 或者做一層 Adapter，把 effectName 映射到你既有的 use-case/service
     */
    public static final class DemoGameEffectHandler implements EffectHandler {

        @Override
        public void handle(String effectName, Map<String, String> parameters, VariableStore variableStore) {
            Objects.requireNonNull(variableStore);

            if (effectName == null || effectName.isBlank()) {
                throw new IllegalArgumentException("effectName is blank");
            }

            switch (effectName) {

                case "addGold": {
                    // 例：<task type="effect" effect="addGold" amount="30"/>
                    int deltaGold = parseIntOrDefault(parameters.get("amount"), 0);
                    int currentGold = parseIntOrDefault(variableStore.get("gold"), 0);
                    int nextGold = currentGold + deltaGold;
                    variableStore.put("gold", String.valueOf(nextGold));
                    System.out.println("[Effect] addGold: " + deltaGold + ", now gold=" + nextGold);
                    break;
                }

                case "setFlag": {
                    // 例：<task type="effect" effect="setFlag" key="questA_done" value="true"/>
                    String flagKey = parameters.get("key");
                    String flagValue = parameters.get("value");
                    if (flagKey == null || flagKey.isBlank()) {
                        throw new IllegalArgumentException("setFlag requires key");
                    }
                    variableStore.put(flagKey, flagValue);
                    System.out.println("[Effect] setFlag: " + flagKey + "=" + flagValue);
                    break;
                }

                case "grantTitle": {
                    // 例：<task type="effect" effect="grantTitle" value="山林行者"/>
                    String titleValue = parameters.get("value");
                    variableStore.put("player_title", titleValue);
                    System.out.println("[Effect] grantTitle: " + titleValue);
                    break;
                }

                default: {
                    throw new IllegalArgumentException("Unknown effect: " + effectName);
                }
            }
        }

        private int parseIntOrDefault(String rawValue, int defaultValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(rawValue.trim());
            } catch (NumberFormatException exception) {
                return defaultValue;
            }
        }
    }

    /**
     * WorkflowLoader：負責載入 XML +（可選）XSD 驗證
     *
     * 教學重點：
     * - XSD 驗證在「載入期」就擋掉 DSL 錯誤，避免跑到一半才爆
     * - 使用 namespace 時，讀取 root/task 需注意 localName / getElementsByTagNameNS
     */
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

            // 教學：有 namespace 時要用 localName 才準；沒有則 tagName
            String rootName = rootElement.getLocalName() != null ? rootElement.getLocalName() : rootElement.getTagName();
            if (!"workflow".equals(rootName)) {
                throw new IllegalArgumentException("Root must be <workflow>, but got: <" + rootElement.getTagName() + ">");
            }

            String workflowId = getAttributeOrDefault(rootElement, "id", "unknown");

            List<TaskDefinition> taskOrderList = new ArrayList<>();
            Map<String, TaskDefinition> taskByIdMap = new LinkedHashMap<>();

            // 教學：同時支援有/無 namespace 的 task 搜尋
            NodeList taskNodeList = rootElement.getElementsByTagNameNS("*", "task");
            if (taskNodeList.getLength() == 0) {
                taskNodeList = rootElement.getElementsByTagName("task");
            }
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
            InputStream inputStream = TaskFlowEffectBridgeDemo.class.getClassLoader().getResourceAsStream(resourcePath);
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

    /**
     * Workflow：流程執行器
     *
     * 教學重點：
     * - 預設順序向下執行（下一個 task）
     * - branch/goto/end 改變流程走向
     * - effect 交給 EffectHandler（橋接到 App/遊戲）
     */
    public static final class Workflow {

        private final String id;
        private final String firstTaskId;
        private final Map<String, TaskDefinition> taskByIdMap;
        private final List<TaskDefinition> taskOrderList;

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
        }

        public void run(
            ChoicePresenter choicePresenter,
            EffectHandler effectHandler,
            VariableStore variableStore
        ) {
            System.out.println("[TaskFlow] Run workflow: " + id);

            String currentTaskId = firstTaskId;

            while (currentTaskId != null) {
                TaskDefinition currentTask = taskByIdMap.get(currentTaskId);
                if (currentTask == null) {
                    throw new IllegalStateException("Task not found: " + currentTaskId);
                }

                ExecutionResult executionResult = execute(currentTask, choicePresenter, effectHandler, variableStore);
                currentTaskId = executionResult.nextTaskId;
            }

            System.out.println("[TaskFlow] Workflow finished: " + id);
        }

        private ExecutionResult execute(
            TaskDefinition taskDefinition,
            ChoicePresenter choicePresenter,
            EffectHandler effectHandler,
            VariableStore variableStore
        ) {
            switch (taskDefinition.type) {

                case "setVar": {
                    // setVar：寫入 workflow state
                    requireNonBlank(taskDefinition.key, "setVar requires key, taskId=" + taskDefinition.id);
                    variableStore.put(taskDefinition.key, interpolate(taskDefinition.value, variableStore));
                    return ExecutionResult.next(getNextSequentialTaskId(taskDefinition.id));
                }

                case "log": {
                    // log：只輸出，不改變流程（走下一個 task）
                    String message = taskDefinition.message == null ? "" : taskDefinition.message;
                    return ExecutionResult.next(getNextSequentialTaskId(taskDefinition.id));
                }

                case "choice": {
                    // choice：顯示 2 選 1 → 將選擇結果寫入 VariableStore
                    requireNonBlank(taskDefinition.key, "choice requires key, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.prompt, "choice requires prompt, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionAText, "choice requires optionA, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionAValue, "choice requires valueA, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionBText, "choice requires optionB, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.optionBValue, "choice requires valueB, taskId=" + taskDefinition.id);

                    String nextTaskIdAfterChoice = getNextSequentialTaskId(taskDefinition.id);

                    choicePresenter.showTwoOptions(
                        interpolate(taskDefinition.prompt, variableStore),
                        interpolate(taskDefinition.optionAText, variableStore),
                        taskDefinition.optionAValue,
                        interpolate(taskDefinition.optionBText, variableStore),
                        taskDefinition.optionBValue,
                        chosenValue -> {
                            variableStore.put(taskDefinition.key, chosenValue);
                        }
                    );

                    return ExecutionResult.next(nextTaskIdAfterChoice);
                }

                case "branch": {
                    // branch：根據 VariableStore 某個 key 的值，跳轉到 thenGo / elseGo
                    requireNonBlank(taskDefinition.ifEqualsKey, "branch requires ifEqualsKey, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.thenGo, "branch requires thenGo, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.elseGo, "branch requires elseGo, taskId=" + taskDefinition.id);

                    String actualValue = variableStore.get(taskDefinition.ifEqualsKey);
                    boolean isMatch = actualValue != null && actualValue.equals(taskDefinition.ifEqualsValue);

                    return ExecutionResult.next(isMatch ? taskDefinition.thenGo : taskDefinition.elseGo);
                }

                case "goto": {
                    // goto：無條件跳轉
                    requireNonBlank(taskDefinition.go, "goto requires go, taskId=" + taskDefinition.id);
                    return ExecutionResult.next(taskDefinition.go);
                }

                case "effect": {
                    // effect：觸發副作用（例如加金幣、設旗標、更新 GameControl）
                    // workflow 不直接做這些事，而是交給 EffectHandler（保持引擎純粹）
                    requireNonBlank(taskDefinition.effect, "effect requires effect, taskId=" + taskDefinition.id);

                    Map<String, String> parameters = new LinkedHashMap<>();

                    // 教學：目前示範只放入 amount / key / value
                    // 你要更通用，可改成讀取 <param> 子節點或 attributes 全打包
                    if (taskDefinition.amount != null) {
                        parameters.put("amount", interpolate(taskDefinition.amount, variableStore));
                    }
                    if (taskDefinition.key != null) {
                        parameters.put("key", interpolate(taskDefinition.key, variableStore));
                    }
                    if (taskDefinition.value != null) {
                        parameters.put("value", interpolate(taskDefinition.value, variableStore));
                    }

                    effectHandler.handle(taskDefinition.effect, parameters, variableStore);

                    return ExecutionResult.next(getNextSequentialTaskId(taskDefinition.id));
                }

                case "end": {
                    return ExecutionResult.end();
                }

                default: {
                    throw new IllegalArgumentException("Unknown task type: " + taskDefinition.type + ", taskId=" + taskDefinition.id);
                }
            }
        }

        /**
         * 插值：把 ${key} 換成 VariableStore 的值
         */
        private String interpolate(String rawText, VariableStore variableStore) {
            if (rawText == null) {
                return "";
            }

            String resolvedText = rawText;
            Map<String, String> snapshot = variableStore.snapshot();

            for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                resolvedText = resolvedText.replace(placeholder, entry.getValue());
            }

            return resolvedText;
        }

        /**
         * 預設順序：如果沒有 branch/goto/end，就走下一個 task
         */
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
    }

    /**
     * ExecutionResult：目前只保留 nextTaskId（最小版）
     */
    public static final class ExecutionResult {

        public final String nextTaskId;

        private ExecutionResult(String nextTaskId) {
            this.nextTaskId = nextTaskId;
        }

        public static ExecutionResult next(String nextTaskId) {
            return new ExecutionResult(nextTaskId);
        }

        public static ExecutionResult end() {
            return new ExecutionResult(null);
        }
    }

    /**
     * TaskDefinition：把 <task ...attributes.../> 映射成 Java 物件
     *
     * 教學重點：
     * - 目前為「最小可用」：用 attributes 表達所有任務參數
     * - 要更強可擴充：允許子節點（例如 <params><param .../></params>）
     */
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

        public final String effect;
        public final String amount;

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
            String go,
            String effect,
            String amount
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
            this.effect = effect;
            this.amount = amount;
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

            String effect = getOptionalAttribute(taskElement, "effect");
            String amount = getOptionalAttribute(taskElement, "amount");

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
                go,
                effect,
                amount
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
