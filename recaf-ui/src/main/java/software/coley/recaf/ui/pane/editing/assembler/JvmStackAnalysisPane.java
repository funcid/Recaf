package software.coley.recaf.ui.pane.editing.assembler;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import dev.xdark.blw.type.ClassType;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTEmpty;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.compile.analysis.*;
import me.darknet.assembler.compile.analysis.frame.Frame;
import me.darknet.assembler.compile.analysis.frame.TypedFrame;
import me.darknet.assembler.compile.analysis.frame.ValuedFrame;
import me.darknet.assembler.parser.Token;
import me.darknet.assembler.parser.TokenType;
import me.darknet.assembler.util.Location;
import me.darknet.assembler.util.Range;
import org.reactfx.EventStreams;
import software.coley.collections.Lists;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.ui.config.TextFormatConfig;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.time.Duration;
import java.util.*;

/**
 * Component panel for the assembler which shows the data from stack analysis of the currently selected method.
 *
 * @author Matt Coley
 */
@Dependent
public class JvmStackAnalysisPane extends AstBuildConsumerComponent {
	private final SimpleObjectProperty<Object> notifyQueue = new SimpleObjectProperty<>(new Object());
	private final TableView<JvmVariableState> varTable = new TableView<>();
	private final TableView<JvmStackState> stackTable = new TableView<>();
	private int lastInsnIndex;

	@Inject
	public JvmStackAnalysisPane(@Nonnull CellConfigurationService cellConfigurationService,
								@Nonnull TextFormatConfig formatConfig,
								@Nonnull Workspace workspace) {
		TableColumn<JvmVariableState, String> columnName = new TableColumn<>(Lang.get("assembler.variables.name"));
		TableColumn<JvmVariableState, ClassType> columnType = new TableColumn<>(Lang.get("assembler.variables.type"));
		TableColumn<JvmVariableState, ValueTableCell.ValueWrapper> columnValue = new TableColumn<>(Lang.get("assembler.variables.value"));
		columnName.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().name));
		columnType.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue().type));
		columnValue.setCellValueFactory(param -> new SimpleObjectProperty<>(new ValueTableCell.ValueWrapper(param.getValue().value, param.getValue().priorValue)));
		columnType.setCellFactory(param -> new TypeTableCell<>(cellConfigurationService, formatConfig, workspace));
		columnValue.setCellFactory(param -> new ValueTableCell<>());
		varTable.getColumns().addAll(columnName, columnType, columnValue);

		TableColumn<JvmStackState, ClassType> columnTypeStack = new TableColumn<>(Lang.get("assembler.analysis.type"));
		TableColumn<JvmStackState, ValueTableCell.ValueWrapper> columnValueStack = new TableColumn<>(Lang.get("assembler.analysis.value"));
		columnTypeStack.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue().type));
		columnValueStack.setCellValueFactory(param -> new SimpleObjectProperty<>(new ValueTableCell.ValueWrapper(param.getValue().value, param.getValue().priorValue)));
		columnTypeStack.setCellFactory(param -> new TypeTableCell<>(cellConfigurationService, formatConfig, workspace));
		columnValueStack.setCellFactory(param -> new ValueTableCell<>());
		stackTable.getColumns().addAll(columnTypeStack, columnValueStack);

		varTable.getStyleClass().addAll(Styles.STRIPED, Tweaks.EDGE_TO_EDGE, "variable-table");
		varTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		stackTable.getStyleClass().addAll(Styles.STRIPED, Tweaks.EDGE_TO_EDGE, "variable-table");
		stackTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

		SplitPane split = new SplitPane(stackTable, varTable);
		setCenter(split);

		EventStreams.changesOf(notifyQueue)
				.reduceSuccessions(Collections::singletonList, Lists::add, Duration.ofMillis(Editor.SHORTER_DELAY_MS))
				.addObserver(unused -> updateTable());
	}

	private void updateTable() {
		stackTable.setDisable(false);
		varTable.setDisable(false);

		// Compute what instruction index the caret is at
		int insnIndex = -1;
		int pos = editor.getCodeArea().getCaretPosition();
		for (ASTElement element : astElements) {
			if (element instanceof ASTMethod method && method.range().within(pos)) {
				List<ASTInstruction> instructions = method.code().instructions();
				int paragraph = editor.getCodeArea().getCurrentParagraph();
				int result = Collections.binarySearch(instructions, new ASTEmpty(new Token(
						new Range(pos, pos + 1),
						new Location(paragraph, 0, null),
						TokenType.IDENTIFIER,
						"."
				)), (o1, o2) -> {
					Location l1 = o1.location();
					Location l2 = o2.location();
					if (l1 == null) return 1;
					else if (l2 == null) return -1;
					return Objects.compare(l1, l2, Comparator.naturalOrder());
				});
				if (result < 0) result = -result;
				insnIndex = Math.min(instructions.size() - 1, result + 1);
				break;
			}
		}

		// If we've not moved, no need to update the table.
		if (lastInsnIndex == insnIndex)
			return;
		lastInsnIndex = insnIndex;

		// Skip for invalid index.
		if (insnIndex < 0)
			return;

		// Skip of no method analysis for the current method.
		AnalysisResults analysisResults = analysisLookup.results(currentMethod.getName(), currentMethod.getDescriptor());
		if (analysisResults == null)
			return;

		// Skip if no frames.
		NavigableMap<Integer, Frame> frames = analysisResults.frames();
		if (frames.isEmpty())
			return;

		// Compute variable/stack states.
		List<JvmVariableState> varItems = new ArrayList<>();
		List<JvmStackState> stackItems = new ArrayList<>();
		var entry = frames.floorEntry(insnIndex);
		var entryKey = entry.getKey();
		Frame thisFrame = entry.getValue();
		if (thisFrame instanceof TypedFrame typedFrame) {
			// Type-only analysis is basic
			for (ClassType classType : typedFrame.getStack())
				stackItems.add(new JvmStackState(classType, Values.valueOf(classType), null));
			for (Local local : typedFrame.getLocals().values())
				varItems.add(new JvmVariableState(local.name(), local.type(), Values.valueOf(local.type()), null));
		} else if (thisFrame instanceof ValuedFrame valuedFrame) {
			// Value analysis will not only track values in a frame, but also let us see if values change across frames
			ValuedFrame lastFrame = entryKey == 0 ? null : (ValuedFrame) frames.floorEntry(entryKey - 1).getValue();

			// Fill out stack.
			Value[] lastStack = lastFrame == null ? new Value[0] : lastFrame.getStack().toArray(Value[]::new);
			Value[] stack = valuedFrame.getStack().toArray(Value[]::new);
			for (int i = 0; i < stack.length; i++) {
				Value lastValue = i <= lastStack.length - 1 ? lastStack[i] : null;
				Value value = stack[i];
				stackItems.add(new JvmStackState(value.type(), value, lastValue));
			}

			// And fill out the variables.
			Map<Integer, ValuedLocal> lastLocals = lastFrame == null ? Collections.emptyMap() : lastFrame.getLocals();
			Map<Integer, ValuedLocal> locals = valuedFrame.getLocals();
			for (ValuedLocal local : locals.values()) {
				ValuedLocal lastLocal = lastLocals.get(local.index());
				varItems.add(new JvmVariableState(local.name(), local.type(), local.value(),
						lastLocal == null ? null : lastLocal.value()));
			}
		}
		varTable.getItems().setAll(varItems);
		stackTable.getItems().setAll(stackItems);
	}

	private void clearData() {
		stackTable.setDisable(true);
		varTable.setDisable(true);
		stackTable.getItems().clear();
		varTable.getItems().clear();
		lastInsnIndex = -1;
	}

	private void scheduleTableUpdate() {
		if (currentMethod == null || analysisLookup == null || editor == null) return;
		notifyQueue.set(new Object());
	}

	@Override
	protected void onClassSelected() {
		clearData();
	}

	@Override
	protected void onMethodSelected() {
		scheduleTableUpdate();
	}

	@Override
	protected void onFieldSelected() {
		clearData();
	}

	@Override
	protected void onPipelineOutputUpdate() {
		scheduleTableUpdate();
	}

	@Override
	public void install(@Nonnull Editor editor) {
		super.install(editor);

		// Not reusing this pane, so we don't need to track for removal
		editor.getCaretPosEventStream()
				.reduceSuccessions(Collections::singletonList, Lists::add, Duration.ofMillis(Editor.SHORT_DELAY_MS))
				.addObserver(e -> scheduleTableUpdate());
	}

	/**
	 * Models the state of a variable.
	 *
	 * @param name
	 * 		Variable name.
	 * @param type
	 * 		Variable type.
	 * @param value
	 * 		Variable value.
	 * @param priorValue
	 * 		Prior state in previous frame, if known.
	 */
	private record JvmVariableState(@Nonnull String name, @Nonnull ClassType type, @Nonnull Value value,
									@Nullable Value priorValue) {}

	/**
	 * Models an item on the stack.
	 *
	 * @param type
	 * 		Type of item.
	 * @param value
	 * 		Value of item.
	 * @param priorValue
	 * 		Prior state in previous frame, if known.
	 */
	private record JvmStackState(@Nonnull ClassType type, @Nonnull Value value, @Nullable Value priorValue) {}
}
