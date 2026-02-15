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

/**
 * TaskFlowTutorial：一個最小可教學的「資料驅動流程引擎」示範。
 *
 * <p>這支程式要教的不是 XML 本身，而是以下 3 層分工：</p>
 * <ol>
 *   <li><b>DSL（XML）</b>：用資料描述流程，不把流程寫死在 Java 程式碼。</li>
 *   <li><b>Validation（XSD）</b>：用 Schema 在載入時就擋掉錯誤格式，避免執行期才爆炸。</li>
 *   <li><b>Engine（Java）</b>：載入後用狀態（variables）+ 任務定義（TaskDefinition）執行流程。</li>
 * </ol>
 *
 * <p>教學建議：你可以讓學生依序改 XML（加任務、改順序、改分支）觀察行為變化，
 * 再示範 XSD 抓錯，以及 ValidationMode 三種模式差異。</p>
 */
public class TaskFlowTutorial {

    /**
     * 教學入口：
     * <ul>
     *   <li>指定要載入的 workflow XML（放在 classpath 下，例如 resources/xml/task1.xml）。</li>
     *   <li>指定驗證模式（NONE/WARN_ONLY/FAIL_FAST）。</li>
     *   <li>載入後取得 Workflow 物件並執行 run()。</li>
     * </ul>
     */
    public static void main(String[] args) {
        String workflowXmlResourcePath = "xml/tutorial.xml";
        ValidationMode validationMode = ValidationMode.FAIL_FAST;

        Workflow workflow = WorkflowLoader.loadFromResources(workflowXmlResourcePath, validationMode);
        workflow.run();
    }

    /**
     * ValidationMode：決定「XSD 驗證」失敗時要怎麼處理。
     * <ul>
     *   <li>NONE：不做 XSD 驗證，速度最快，但風險最高（錯誤會延後到 parse 或 run 才爆）。</li>
     *   <li>WARN_ONLY：做驗證，但只印錯誤不擋流程（適合開發期快速迭代）。</li>
     *   <li>FAIL_FAST：驗證失敗立刻丟例外（適合正式版／CI／避免錯誤資料進入引擎）。</li>
     * </ul>
     */
    public enum ValidationMode {
        NONE,      // 不驗證
        WARN_ONLY, // 驗證但不丟例外，只印出錯誤
        FAIL_FAST  // 驗證失敗就丟例外
    }

    /**
     * WorkflowLoader：負責「載入 + 驗證 + 解析」。
     *
     * <p>重要教學點：</p>
     * <ul>
     *   <li>Loader 只做「資料轉換」，不做「流程執行」。</li>
     *   <li>先驗證再解析：把錯誤提早到載入期處理。</li>
     *   <li>Schema 使用 cachedSchema 快取，避免重複讀取 XSD。</li>
     * </ul>
     */
    public static final class WorkflowLoader {

        /**
         * XSD 放在 classpath 根目錄下（例如 resources/workflow.xsd）。
         */
        private static final String XSD_RESOURCE_PATH = "workflow.xsd";

        /**
         * Schema 快取：
         * <ul>
         *   <li>volatile：確保多執行緒讀取 cachedSchema 時可見性正確。</li>
         *   <li>搭配 synchronized 做 double-checked locking，避免重複建立 Schema。</li>
         * </ul>
         */
        private static volatile Schema cachedSchema;

        /**
         * 從 classpath 讀取 XML，並依 ValidationMode 進行驗證，最後解析成 Workflow。
         *
         * <p>回傳的 Workflow 是「可執行資料」：它包含任務列表、索引 Map、第一個 task 等資訊。</p>
         */
        public static Workflow loadFromResources(String xmlResourcePath, ValidationMode validationMode) {

            // 1) 先做 XSD 驗證（可選），把資料格式問題盡早抓出來
            if (validationMode != ValidationMode.NONE) {
                validateXmlFromResources(xmlResourcePath, validationMode);
            }

            // 2) 再解析 XML 成 DOM Document（此處不套 schema，單純 parse）
            Document document = parseXmlDocumentFromResources(xmlResourcePath);

            // 3) 取 root element 並做基本健全性檢查（比 XSD 更偏「程式防呆」）
            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new IllegalArgumentException("XML root is null: " + xmlResourcePath);
            }
            if (!"workflow".equals(rootElement.getTagName())) {
                throw new IllegalArgumentException("Root must be <workflow>, but got: <" + rootElement.getTagName() + ">");
            }

            // 4) workflowId：允許缺省，避免教學 demo 因為忘記填 id 就整個不能跑
            String workflowId = getAttributeOrDefault(rootElement, "id", "unknown");

            // 5) taskOrderList：保留 XML 中的原始順序（用於「順序執行」）
            List<TaskDefinition> taskOrderList = new ArrayList<>();

            // 6) taskByIdMap：用 id 快速查 task（用於 branch 跳轉 / 查詢）
            Map<String, TaskDefinition> taskByIdMap = new LinkedHashMap<>();

            // 7) 取出所有 <task> 節點（注意：getElementsByTagName 會抓到所有後代）
            NodeList taskNodeList = rootElement.getElementsByTagName("task");
            if (taskNodeList.getLength() == 0) {
                throw new IllegalArgumentException("Workflow has no <task>: " + xmlResourcePath);
            }

            // 8) 逐一解析每個 task element → TaskDefinition
            for (int index = 0; index < taskNodeList.getLength(); index++) {
                Element taskElement = (Element) taskNodeList.item(index);

                TaskDefinition taskDefinition = TaskDefinition.from(taskElement);

                // 9) id 唯一性檢查：避免 branch 跳轉混亂（同 id 會覆蓋或造成不可預期）
                if (taskByIdMap.containsKey(taskDefinition.id)) {
                    throw new IllegalArgumentException("Duplicate task id: " + taskDefinition.id);
                }

                taskOrderList.add(taskDefinition);
                taskByIdMap.put(taskDefinition.id, taskDefinition);
            }

            // 10) 預設從第一個 task 開始跑（也可改成 XML 指定 startId）
            String firstTaskId = taskOrderList.get(0).id;
            return new Workflow(workflowId, firstTaskId, taskByIdMap, taskOrderList);
        }

        /**
         * validateXmlFromResources：用 XSD 驗證 XML 結構。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>DocumentBuilderFactory.setSchema(schema)：讓 parse 時同時驗證。</li>
         *   <li>FAIL_FAST：丟例外中止；WARN_ONLY：印錯誤但繼續。</li>
         * </ul>
         */
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

        /**
         * parseXmlDocumentFromResources：讀取 XML 並 parse 成 DOM Document。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>這裡不做 XSD 驗證（驗證已在 validateXmlFromResources 做完）。</li>
         *   <li>分離「驗證」與「解析」可以讓錯誤訊息更聚焦，也更容易測試。</li>
         * </ul>
         */
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

        /**
         * getOrCreateSchemaFromResources：載入並快取 XSD Schema。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>SchemaFactory 使用 W3C XML Schema namespace。</li>
         *   <li>快取 Schema 避免重複 IO 與解析成本。</li>
         * </ul>
         */
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

        /**
         * openResourceStream：從 classpath 讀資源。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>resourcePath 必須與 resources 內的相對路徑一致。</li>
         *   <li>找不到資源時，立刻丟例外，避免後續 NullPointerException。</li>
         * </ul>
         */
        private static InputStream openResourceStream(String resourcePath) {
            InputStream inputStream = TaskFlowTutorial.class.getClassLoader().getResourceAsStream(resourcePath);
            if (inputStream == null) {
                throw new IllegalStateException("Resource not found in classpath: " + resourcePath);
            }
            return inputStream;
        }

        /**
         * 讀取 element 的 attribute，若空白則回傳 defaultValue。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>把「缺省處理」集中在 helper，避免散落在各處造成重複碼。</li>
         * </ul>
         */
        private static String getAttributeOrDefault(Element element, String attributeName, String defaultValue) {
            String value = element.getAttribute(attributeName);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return value;
        }
    }

    /**
     * Workflow：流程執行引擎（runtime）。
     *
     * <p>教學點：</p>
     * <ul>
     *   <li>workflow.run() 以「currentTaskId」作為程式計數器（類似簡化版 state machine）。</li>
     *   <li>variables 是執行期狀態（State），task 是規則（System）。</li>
     *   <li>setVar/log/branch/end 是最小可教學的 instruction set。</li>
     * </ul>
     */
    public static final class Workflow {

        private final String id;
        private final String firstTaskId;
        private final Map<String, TaskDefinition> taskByIdMap;
        private final List<TaskDefinition> taskOrderList;

        /**
         * variables：流程執行期的狀態（State）。
         * 這裡用 Map 是最簡單的做法，後續你可教：
         * <ul>
         *   <li>型別系統（string/int/bool）</li>
         *   <li>scope（global/local）</li>
         *   <li>expression（更進階的條件判斷）</li>
         * </ul>
         */
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

        /**
         * run：流程主迴圈。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>currentTaskId = program counter。</li>
         *   <li>execute 回傳 nextTaskId，決定下一步走哪個 task。</li>
         *   <li>回傳 null 表示流程結束。</li>
         * </ul>
         */
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

        /**
         * execute：執行單一 TaskDefinition。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>switch(type) 代表一個 instruction dispatch。</li>
         *   <li>每種 type 的必要欄位要先 requireNonBlank，避免 silent fail。</li>
         * </ul>
         */
        private String execute(TaskDefinition taskDefinition) {
            switch (taskDefinition.type) {

                case "setVar":
                    // setVar：寫入執行期狀態（variables）
                    requireNonBlank(taskDefinition.key, "setVar requires key, taskId=" + taskDefinition.id);
                    variables.put(taskDefinition.key, taskDefinition.value == null ? "" : taskDefinition.value);
                    // 順序執行：走下一個 task
                    return getNextSequentialTaskId(taskDefinition.id);

                case "log":
                    // log：印訊息（會先做變數插值）
                    String message = taskDefinition.message == null ? "" : taskDefinition.message;
                    log("[TaskFlow] " + interpolate(message));
                    return getNextSequentialTaskId(taskDefinition.id);

                case "branch":
                    // branch：根據 variables 的值分支跳轉
                    requireNonBlank(taskDefinition.ifEqualsKey, "branch requires ifEqualsKey, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.thenGo, "branch requires thenGo, taskId=" + taskDefinition.id);
                    requireNonBlank(taskDefinition.elseGo, "branch requires elseGo, taskId=" + taskDefinition.id);

                    String actualValue = variables.get(taskDefinition.ifEqualsKey);
                    boolean isMatch = actualValue != null && actualValue.equals(taskDefinition.ifEqualsValue);

                    // 分支跳轉：回傳 thenGo 或 elseGo 作為 nextTaskId
                    return isMatch ? taskDefinition.thenGo : taskDefinition.elseGo;

                case "end":
                    // end：流程結束
                    return null;

                default:
                    // 未知 instruction：直接拋錯，避免流程靜默失敗
                    throw new IllegalArgumentException("Unknown task type: " + taskDefinition.type + ", taskId=" + taskDefinition.id);
            }
        }

        /**
         * interpolate：最簡版字串插值。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>${key} → variables.get(key)</li>
         *   <li>這裡採用「全表掃描 replace」，容易理解，但效率不是最佳。</li>
         * </ul>
         *
         * <p>效能備註：</p>
         * variables 數量為 V、字串長度為 L，粗略成本 O(V * L)。
         * 教學版合理；正式版可改成 tokenization / regex / 單次掃描。</p>
         */
        private String interpolate(String rawText) {
            String resolvedText = rawText;

            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                resolvedText = resolvedText.replace(placeholder, entry.getValue());
            }

            return resolvedText;
        }

        /**
         * getNextSequentialTaskId：取得「順序下一個」taskId。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>使用 taskOrderList 作為順序來源。</li>
         *   <li>branch 不走這裡；branch 直接回傳 thenGo / elseGo。</li>
         * </ul>
         *
         * <p>效能備註：</p>
         * 目前是線性搜尋 O(N)。教學版 OK。
         * 若要優化，可在建構 Workflow 時建立「id → index」Map，變成 O(1)。</p>
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

        /**
         * requireNonBlank：教學用的「前置條件檢查」。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>比起 NullPointerException，這種錯誤訊息更可讀、更可追。</li>
         *   <li>也示範了「資料不可信」的防禦式程式設計。</li>
         * </ul>
         */
        private void requireNonBlank(String value, String messageIfInvalid) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(messageIfInvalid);
            }
        }

        /**
         * log：集中處理輸出，方便日後替換成 logger 或 UI console。
         */
        private void log(String message) {
            System.out.println(message);
        }
    }

    /**
     * TaskDefinition：從 XML 讀進來的「任務宣告」。
     *
     * <p>教學點：</p>
     * <ul>
     *   <li>這是資料（data），不是行為（behavior）。</li>
     *   <li>execute() 才是行為；TaskDefinition 只描述要做什麼。</li>
     *   <li>欄位採 final：載入後不可變，避免 runtime 被修改造成難追 bug。</li>
     * </ul>
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

        /**
         * from：把 <task .../> Element 轉成 TaskDefinition。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>先讀必要欄位（id/type），缺少就丟例外。</li>
         *   <li>再讀可選欄位（key/value/message/branch 欄位）。</li>
         *   <li>不同 type 會用到不同欄位，真正的檢查在 execute() 裡做。</li>
         * </ul>
         */
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

        /**
         * getOptionalAttribute：讀 attribute，空白則回傳 null。
         *
         * <p>教學點：</p>
         * <ul>
         *   <li>可選欄位用 null 表示「未提供」，比空字串更語意化。</li>
         *   <li>真正需要時再由 execute() 做 requireNonBlank。</li>
         * </ul>
         */
        private static String getOptionalAttribute(Element element, String attributeName) {
            String value = element.getAttribute(attributeName);
            if (value == null || value.isBlank()) {
                return null;
            }
            return value;
        }
    }
}
