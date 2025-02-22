/*******************************************************************************
 * Copyright (c) 2016, 2024 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 *  Amit Kumar Mondal
 *
 *******************************************************************************/
/**
 * Render the Content in the Wire Component Properties Panel based on Service (GwtBSConfigComponent) selected in Wire graph
 *
 * Fields are rendered based on their type (Password(Input), Choice(Dropboxes) etc. with Text fields rendered
 * for both numeric and other textual field with validate() checking if value in numeric fields is numeric
 */
package org.eclipse.kura.web.client.ui.drivers.assets;

import static org.eclipse.kura.web.shared.AssetConstants.CHANNEL_PROPERTY_SEPARATOR;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.kura.web.Console;
import org.eclipse.kura.web.client.configuration.HasConfiguration;
import org.eclipse.kura.web.client.ui.AbstractServicesUi;
import org.eclipse.kura.web.client.ui.EntryClassUi;
import org.eclipse.kura.web.client.ui.drivers.assets.AssetModel.ChannelModel;
import org.eclipse.kura.web.client.ui.wires.ValidationData;
import org.eclipse.kura.web.client.util.DownloadHelper;
import org.eclipse.kura.web.client.util.FailureHandler;
import org.eclipse.kura.web.client.util.ResizableTableHeader;
import org.eclipse.kura.web.client.util.request.RequestContext;
import org.eclipse.kura.web.client.util.request.RequestQueue;
import org.eclipse.kura.web.shared.AssetConstants;
import org.eclipse.kura.web.shared.model.GwtConfigComponent;
import org.eclipse.kura.web.shared.model.GwtConfigParameter;
import org.eclipse.kura.web.shared.model.GwtConfigParameter.GwtConfigParameterType;
import org.eclipse.kura.web.shared.model.GwtXSRFToken;
import org.eclipse.kura.web.shared.service.GwtDriverAndAssetService;
import org.eclipse.kura.web.shared.service.GwtDriverAndAssetServiceAsync;
import org.eclipse.kura.web.shared.service.GwtSecurityTokenService;
import org.eclipse.kura.web.shared.service.GwtSecurityTokenServiceAsync;
import org.gwtbootstrap3.client.ui.Anchor;
import org.gwtbootstrap3.client.ui.Button;
import org.gwtbootstrap3.client.ui.CheckBox;
import org.gwtbootstrap3.client.ui.FieldSet;
import org.gwtbootstrap3.client.ui.Form;
import org.gwtbootstrap3.client.ui.FormGroup;
import org.gwtbootstrap3.client.ui.FormLabel;
import org.gwtbootstrap3.client.ui.Modal;
import org.gwtbootstrap3.client.ui.Panel;
import org.gwtbootstrap3.client.ui.PanelFooter;
import org.gwtbootstrap3.client.ui.TextBox;
import org.gwtbootstrap3.client.ui.base.form.AbstractForm.SubmitCompleteEvent;
import org.gwtbootstrap3.client.ui.gwt.CellTable;
import org.gwtbootstrap3.client.ui.html.Paragraph;
import org.gwtbootstrap3.client.ui.html.Strong;

import com.google.gwt.cell.client.AbstractCell;
import com.google.gwt.cell.client.Cell;
import com.google.gwt.cell.client.Cell.Context;
import com.google.gwt.cell.client.SelectionCell;
import com.google.gwt.cell.client.TextCell;
import com.google.gwt.cell.client.TextInputCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.storage.client.Storage;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.Header;
import com.google.gwt.user.cellview.client.SimplePager;
import com.google.gwt.user.cellview.client.SimplePager.TextLocation;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FileUpload;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.Hidden;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.HasRows;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SingleSelectionModel;

public class AssetConfigurationUi extends AbstractServicesUi implements HasConfiguration {

    private static final String COLUMN_VISIBILITY_SETTINGS_KEY = "org.eclipse.kura.settings.column.asset.";

    private static final Logger logger = Logger.getLogger(AssetConfigurationUi.class.getSimpleName());

    private static final List<String> defaultHiddenColumns = //
            Arrays.asList(//
                    AssetConstants.VALUE_UNIT.value(), //
                    AssetConstants.VALUE_SCALE.value(), //
                    AssetConstants.VALUE_OFFSET.value(), //
                    AssetConstants.SCALE_OFFSET_TYPE.value());

    private final GwtSecurityTokenServiceAsync gwtXSRFService = GWT.create(GwtSecurityTokenService.class);
    private final GwtDriverAndAssetServiceAsync gwtAssetService = GWT.create(GwtDriverAndAssetService.class);

    interface AssetConfigurationUiBinder extends UiBinder<Widget, AssetConfigurationUi> {
    }

    @UiField
    Button btnAdd;
    @UiField
    Button btnRemove;
    @UiField
    Button btnDownload;
    @UiField
    Button btnUpload;

    @UiField
    Button btnManageHiddenColumn;
    @UiField
    Form columnVisibilityForm;

    @UiField
    Panel channelPanel;

    @UiField
    CellTable<ChannelModel> channelTable;

    CheckBox selectAllColumnCheckbox;
    List<CheckBox> columnVisibilityCheckBoxes = new ArrayList<>();
    @UiField
    Anchor resetColumnsAnchor;

    @UiField
    FormLabel columnVisibilityError;
    @UiField
    FormLabel columnVisibilityLabel;
    @UiField
    Button btnCancelSetColumnVisibility;
    @UiField
    Button btnSetColumnVisibility;

    @UiField
    Strong channelTitle;
    @UiField
    FieldSet fields;

    @UiField
    Modal newChannelModal;
    @UiField
    Modal columnVisibilityModal;
    @UiField
    FormLabel newChannelNameLabel;
    @UiField
    FormLabel newChannelNameError;
    @UiField
    TextBox newChannelNameInput;
    @UiField
    Button btnCreateNewChannel;
    @UiField
    Button btnCancelCreatingNewChannel;

    @UiField
    Modal uploadModal;

    @UiField
    Form uploadForm;

    @UiField
    Button uploadCancel;
    @UiField
    Button uploadUpload;
    @UiField
    FileUpload filePath;
    @UiField
    CheckBox appendCheck;
    @UiField
    CheckBox emptyStringCheck;
    @UiField
    Hidden xsrfTokenField;
    @UiField
    Hidden assetPidField;
    @UiField
    Hidden driverPidField;
    @UiField
    Hidden emptyStringCheckField;
    @UiField
    Hidden appendCheckField;
    @UiField
    Paragraph emptyTableLabel;
    @UiField
    PanelFooter tablePanelFooter;

    private static final String INVALID_CLASS_NAME = "error-text-box";

    private static final int MAXIMUM_PAGE_SIZE = 15;

    private static AssetConfigurationUiBinder uiBinder = GWT.create(AssetConfigurationUiBinder.class);

    private final ListDataProvider<ChannelModel> channelsDataProvider = new ListDataProvider<>();

    private final SingleSelectionModel<ChannelModel> selectionModel = new SingleSelectionModel<>();

    private static final String SERVLET_URL = Console.ADMIN_ROOT + '/' + GWT.getModuleName() + "/file/asset";

    private final Set<String> invalidParameters;

    private boolean dirty;

    private AssetModel model;
    private ColumnVisibilityMap columnNameVisibilityMap = new ColumnVisibilityMap();
    private final Widget associatedView;

    private final SimplePager channelPager;

    private HasConfiguration.Listener listener;

    private AsyncCallback<SubmitCompleteEvent> formSubmitCallback;

    private Storage localStorage = Storage.getLocalStorageIfSupported();

    public AssetConfigurationUi(final AssetModel assetModel, final Widget associatedView) {
        initWidget(uiBinder.createAndBindUi(this));

        this.model = assetModel;
        this.fields.clear();

        this.channelPager = new SimplePager(TextLocation.CENTER, false, 0, true) {

            @Override
            public void nextPage() {
                setPage(getPage() + 1);
            }

            @Override
            public void setPageStart(int index) {
                final HasRows display = getDisplay();
                if (display != null) {
                    display.setVisibleRange(index, getPageSize());
                }
            }
        };
        this.channelPager.setPageSize(MAXIMUM_PAGE_SIZE);
        this.channelPager.setDisplay(this.channelTable);
        this.channelTable.setSelectionModel(this.selectionModel);
        this.tablePanelFooter.add(this.channelPager);
        this.channelsDataProvider.addDataDisplay(this.channelTable);
        this.channelPanel.setVisible(false);
        this.btnRemove.setEnabled(false);
        this.associatedView = associatedView;

        this.invalidParameters = new HashSet<>();

        this.channelTable.setAutoFooterRefreshDisabled(true);
        this.channelTable.setAutoHeaderRefreshDisabled(true);

        this.btnDownload.setEnabled(true);
        this.btnDownload.addClickHandler(event -> downloadChannels());

        this.btnUpload.addClickHandler(event -> uploadAndApply());

        this.uploadUpload.addClickHandler(click -> RequestQueue
                .submit(context -> this.gwtXSRFService.generateSecurityToken(context.callback(token -> {
                    AssetConfigurationUi.this.xsrfTokenField.setValue(token.getToken());
                    AssetConfigurationUi.this.assetPidField.setValue(AssetConfigurationUi.this.model.getAssetPid());
                    AssetConfigurationUi.this.driverPidField.setValue(AssetConfigurationUi.this.model.getConfiguration()
                            .getParameterValue(AssetConstants.ASSET_DRIVER_PROP.value()));
                    AssetConfigurationUi.this.appendCheckField
                            .setValue(AssetConfigurationUi.this.appendCheck.getValue().toString());
                    AssetConfigurationUi.this.emptyStringCheckField
                            .setValue(AssetConfigurationUi.this.emptyStringCheck.getValue().toString());
                    AssetConfigurationUi.this.uploadForm.submit();
                    AssetConfigurationUi.this.uploadModal.hide();
                    AssetConfigurationUi.this.formSubmitCallback = context.callback(completeEvent -> {
                        String htmlResponse = completeEvent.getResults();
                        if (htmlResponse == null || htmlResponse.isEmpty()) {
                            AssetConfigurationUi.this.gwtXSRFService.generateSecurityToken(context
                                    .callback(t -> fetchUploadedChannels(t, this.appendCheck.getValue(), context)));

                        } else {
                            EntryClassUi.hideWaitModal();
                            logger.log(Level.SEVERE, MSGS.information() + ": " + MSGS.fileUploadFailure());
                            FailureHandler.handle(new Exception(htmlResponse));
                        }
                    });

                }))));

        this.uploadCancel.addClickHandler(event -> AssetConfigurationUi.this.uploadModal.hide());

        this.btnAdd.addClickHandler(event -> {
            AssetConfigurationUi.this.newChannelNameInput.setText(getNewChannelName());
            AssetConfigurationUi.this.newChannelModal.show();
        });

        this.btnManageHiddenColumn.addClickHandler(event -> {
            fillColumnVisibilityCheckBox();
            AssetConfigurationUi.this.columnVisibilityModal.show();
        });

        this.selectionModel.addSelectionChangeHandler(event -> AssetConfigurationUi.this.btnRemove
                .setEnabled(AssetConfigurationUi.this.selectionModel.getSelectedObject() != null));

        this.uploadForm.addSubmitCompleteHandler(event -> this.formSubmitCallback.onSuccess(event));

        this.filePath.getElement().setAttribute("accept", ".csv");

        initColumnVisibilityMap();
        loadColumnVisibilityMap();

        setModel(assetModel);

        initNewChannelModal();
        initColumnVisibilityModal();

        this.btnManageHiddenColumn.setText(MSGS.columnVisibilityModalButton(//
                String.valueOf(totalEnabledColumns(this.columnNameVisibilityMap)),
                String.valueOf(this.columnNameVisibilityMap.size())));

        logger.info("created AssetConfigurationUi for: " + this.model.getAssetPid());
    }

    private void fillColumnVisibilityCheckBox() {
        this.columnVisibilityForm.clear();

        this.selectAllColumnCheckbox = new CheckBox(MSGS.columnVisibilitySelectAllColumnCheckbox());

        this.selectAllColumnCheckbox.addClickHandler(event -> this.columnVisibilityCheckBoxes
                .forEach(checkbox -> checkbox.setValue(this.selectAllColumnCheckbox.getValue())));

        this.columnVisibilityForm.add(this.selectAllColumnCheckbox);

        this.columnNameVisibilityMap.forEach((columnId, visible) -> {

            CheckBox columnCheckBox = new CheckBox(toChannelPropertyName(columnId));
            columnCheckBox.setFormValue(columnId);
            columnCheckBox.setValue(visible);

            AssetConfigurationUi.this.columnVisibilityCheckBoxes.add(columnCheckBox);
            AssetConfigurationUi.this.columnVisibilityForm.add(columnCheckBox);
        });

    }

    private void setColumnVisibilityMap() {
        this.columnVisibilityCheckBoxes
                .forEach(checkbox -> this.columnNameVisibilityMap.put(checkbox.getFormValue(), checkbox.getValue()));
    }

    private void fetchUploadedChannels(GwtXSRFToken token, final boolean replace, final RequestContext context) {
        final String assetPid = this.model.getAssetPid();

        this.gwtAssetService.getUploadedCsvConfig(token, assetPid, context.callback(newConfiguration -> {
            final GwtConfigComponent descriptor = this.model.getChannelDescriptor();

            final AssetModelImpl importedChannels = new AssetModelImpl(newConfiguration, descriptor);

            if (replace) {
                this.model.replaceChannels(importedChannels);
            } else {
                this.model.addAllChannels(importedChannels);
            }

            renderForm();
            setDirty(true);
        }));
    }

    public void setModel(AssetModel model) {
        this.model = model;

        AssetConfigurationUi.this.channelTitle.setText(MSGS.channelTableTitle(
                model.getConfiguration().getParameterValue(AssetConstants.ASSET_DRIVER_PROP.value())));
        renderForm();
        this.channelTable.redraw();
        setDirty(false);
    }

    private void initColumnVisibilityMap() {
        populateColumnVisibilityMap();
    }

    private void resetDefaultColumnVisibilityMap() {
        this.columnNameVisibilityMap.clear();
        populateColumnVisibilityMap();
    }

    private void populateColumnVisibilityMap() {
        this.model.getChannelDescriptor().getParameters().forEach(param -> {
            AssetConfigurationUi.logger.info("Id: " + param.getId() + ", Name: " + param.getName());
            if (!param.getId().equals(AssetConstants.ENABLED.value())
                    && !param.getId().equals(AssetConstants.NAME.value())) {
                boolean visible = !defaultHiddenColumns.contains(param.getId());
                this.columnNameVisibilityMap.put(param.getId(), visible);
            }
        });
    }

    @Override
    public void renderForm() {
        this.fields.clear();

        final GwtConfigComponent nonChannelFields = new GwtConfigComponent();

        for (final GwtConfigParameter param : this.model.getConfiguration().getParameters()) {
            final String[] tokens = param.getId().split(CHANNEL_PROPERTY_SEPARATOR.value());
            boolean isChannelData = tokens.length == 2;
            final boolean isDriverField = param.getId().equals(AssetConstants.ASSET_DRIVER_PROP.value());

            if (!isChannelData && !isDriverField) {
                nonChannelFields.getParameters().add(param);
                if (param.getCardinality() == 0 || param.getCardinality() == 1 || param.getCardinality() == -1) {
                    final FormGroup formGroup = new FormGroup();
                    renderConfigParameter(param, true, formGroup);
                } else {
                    renderMultiFieldConfigParameter(param);
                }
            }
        }

        this.configurableComponent = nonChannelFields;
        initTable();

    }

    private void initTable() {

        int columnCount = AssetConfigurationUi.this.channelTable.getColumnCount();
        for (int i = 0; i < columnCount; i++) {
            AssetConfigurationUi.this.channelTable.removeColumn(0);
        }

        for (final GwtConfigParameter param : this.model.getChannelDescriptor().getParameters()) {
            if (Boolean.TRUE.equals(this.columnNameVisibilityMap.getOrDefault(param.getId(), true))) {
                addColumn(param);
            }
        }

        this.channelsDataProvider.setList(this.model.getChannels());
        this.channelsDataProvider.refresh();
        this.channelPanel.setVisible(true);
        handleChannelTableVisibility();
    }

    private void addColumn(final GwtConfigParameter param) {
        AssetConfigurationUi.this.channelTable.addColumn(
                getColumnFromParam(param, param.getId().equals(AssetConstants.NAME.value())), buildHeader(param));
    }

    @Override
    public void setDirty(final boolean flag) {
        boolean isDirtyStateChanged = flag != this.dirty;
        this.dirty = flag;

        this.btnDownload.setEnabled(!this.model.getChannels().isEmpty() && isValid());

        if (this.listener != null) {
            if (isDirtyStateChanged) {
                this.listener.onDirtyStateChanged(this);
            }
            if (this.dirty) {
                this.listener.onConfigurationChanged(this);
            }
        }
    }

    public Header<?> buildHeader(final GwtConfigParameter param) {
        final String name = param.getName();
        final String description = param.getDescription();
        final String tooltip = name + (description != null ? " - " + description : "");

        return new ResizableTableHeader(name, tooltip);
    }

    @Override
    protected void renderBooleanField(final GwtConfigParameter param, boolean isFirstInstance, FormGroup formGroup) {
        super.renderBooleanField(param, isFirstInstance, formGroup);
        this.fields.add(formGroup);
    }

    @Override
    protected void renderChoiceField(final GwtConfigParameter param, boolean isFirstInstance, FormGroup formGroup) {
        super.renderChoiceField(param, isFirstInstance, formGroup);
        this.fields.add(formGroup);
    }

    @Override
    protected void renderPasswordField(final GwtConfigParameter param, boolean isFirstInstance, FormGroup formGroup) {
        super.renderPasswordField(param, isFirstInstance, formGroup);
        this.fields.add(formGroup);
    }

    @Override
    protected void renderTextField(final GwtConfigParameter param, boolean isFirstInstance, final FormGroup formGroup) {
        super.renderTextField(param, isFirstInstance, formGroup);
        this.fields.add(formGroup);
    }

    @Override
    public void reset() {
        // Not needed
    }

    private Column<ChannelModel, String> getColumnFromParam(final GwtConfigParameter param, boolean isReadOnly) {
        final Map<String, String> options = param.getOptions();
        if (options != null && options.size() > 0) {
            return getSelectionInputColumn(param, isReadOnly);
        } else {
            return getInputCellColumn(param, isReadOnly);
        }
    }

    private Column<ChannelModel, String> getInputCellColumn(final GwtConfigParameter param, boolean isReadOnly) {
        final AbstractCell<String> cell;
        if (isReadOnly) {
            cell = new TextCell();
        } else if (param.getType() == GwtConfigParameterType.BOOLEAN) {
            cell = new BooleanInputCell();
        } else {
            cell = new TextInputCell();
        }

        final Column<ChannelModel, String> result = new ChannelColumn(cell, param);

        if (!isReadOnly) {
            result.setFieldUpdater((index, object, value) -> {
                final String paramId = object.getChannelName() + '#' + param.getId();
                object.setValue(param.getId(), value);
                if (!object.isValid(param.getId())) {
                    AssetConfigurationUi.this.invalidParameters.add(paramId);
                } else {
                    AssetConfigurationUi.this.invalidParameters.remove(paramId);
                }
                AssetConfigurationUi.this.setDirty(true);
                this.channelTable.redrawRow(index);
            });

        }

        if (param.getType() == GwtConfigParameterType.BOOLEAN) {
            result.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
        }

        return result;
    }

    private Column<ChannelModel, String> getSelectionInputColumn(final GwtConfigParameter param, boolean isReadOnly) {
        final String id = param.getId();
        final Map<String, String> labelsToValues = param.getOptions();
        ArrayList<Entry<String, String>> sortedLabelsToValues = new ArrayList<>(labelsToValues.entrySet());
        Collections.sort(sortedLabelsToValues, DROPDOWN_LABEL_COMPARATOR);
        final ArrayList<String> labels = new ArrayList<>();
        final Map<String, String> valuesToLabels = new HashMap<>();
        for (Entry<String, String> entry : sortedLabelsToValues) {
            labels.add(entry.getKey());
            valuesToLabels.put(entry.getValue(), entry.getKey());
        }
        final SelectionCell cell = new SelectionCell(new ArrayList<>(labels));
        final Column<ChannelModel, String> result = new Column<ChannelModel, String>(cell) {

            @Override
            public String getValue(final ChannelModel object) {
                String result = object.getValue(id);
                if (result == null) {
                    final String defaultValue = param.getDefault();
                    result = defaultValue != null ? defaultValue : labelsToValues.get(labels.get(0));
                    object.setValue(id, result);
                }
                return valuesToLabels.get(result);
            }
        };

        if (!isReadOnly) {
            result.setFieldUpdater((index, object, label) -> {
                AssetConfigurationUi.this.setDirty(true);
                object.setValue(param.getId(), labelsToValues.get(label));
            });
        }

        return result;
    }

    private void initNewChannelModal() {
        this.newChannelModal.setTitle(MSGS.wiresCreateNewChannel());
        this.newChannelNameLabel.setText(MSGS.wiresCreateNewChannelName());
        this.btnCreateNewChannel.setText(MSGS.addButton());
        this.btnCancelCreatingNewChannel.setText(MSGS.cancelButton());

        this.newChannelNameInput.addKeyUpHandler(event -> {
            ValidationData isChannelNameValid = validateChannelName(
                    AssetConfigurationUi.this.newChannelNameInput.getValue().trim());
            if (isChannelNameValid.isInvalid()) {
                AssetConfigurationUi.this.newChannelNameInput.addStyleName(INVALID_CLASS_NAME);
                AssetConfigurationUi.this.newChannelNameError.setText(isChannelNameValid.getValue());
                return;
            }
            AssetConfigurationUi.this.newChannelNameError.setText("");
            AssetConfigurationUi.this.newChannelNameInput.removeStyleName(INVALID_CLASS_NAME);
        });

        this.btnCreateNewChannel.addClickHandler(event -> {
            final String newChannelName = AssetConfigurationUi.this.newChannelNameInput.getValue().trim();

            ValidationData isChannelNameValid = validateChannelName(newChannelName);
            if (isChannelNameValid.isInvalid()) {
                return;
            }

            AssetConfigurationUi.this.model.createNewChannel(newChannelName);

            AssetConfigurationUi.this.channelsDataProvider.setList(AssetConfigurationUi.this.model.getChannels());
            AssetConfigurationUi.this.channelsDataProvider.refresh();
            AssetConfigurationUi.this.channelPager.lastPage();
            AssetConfigurationUi.this.setDirty(true);
            AssetConfigurationUi.this.newChannelModal.hide();
            handleChannelTableVisibility();
        });

        this.btnRemove.addClickHandler(event -> {
            final int startIndex = this.channelPager.getPageStart();

            final ChannelModel ci = AssetConfigurationUi.this.selectionModel.getSelectedObject();
            AssetConfigurationUi.this.model.deleteChannel(ci.getChannelName());

            AssetConfigurationUi.this.channelsDataProvider.setList(AssetConfigurationUi.this.model.getChannels());
            AssetConfigurationUi.this.channelsDataProvider.refresh();
            this.channelPager.setPageStart(startIndex);
            AssetConfigurationUi.this.btnRemove.setEnabled(false);
            AssetConfigurationUi.this.setDirty(true);
            handleChannelTableVisibility();
        });
    }

    private void initColumnVisibilityModal() {

        this.columnVisibilityLabel.setText(MSGS.columnVisibilityLabel());
        this.btnCancelSetColumnVisibility.setText(MSGS.columnVisibilityButtonCancel());
        this.btnSetColumnVisibility.setText(MSGS.columnVisibilityButtonSet());

        fillColumnVisibilityCheckBox();

        this.resetColumnsAnchor.addClickHandler(event -> {
            resetDefaultColumnVisibilityMap();
            fillColumnVisibilityCheckBox();
        });

        this.btnSetColumnVisibility.addClickHandler(event -> {
            setColumnVisibilityMap();

            if (!validateColumnVisibility(this.columnNameVisibilityMap)) {
                AssetConfigurationUi.this.columnVisibilityError.setText(MSGS.columnVisibilityErrorLabel());
                return;
            }

            saveColumnVisibility();

            AssetConfigurationUi.this.columnVisibilityError.setText("");
            initTable();
            this.btnManageHiddenColumn.setText(MSGS.columnVisibilityModalButton(//
                    String.valueOf(totalEnabledColumns(this.columnNameVisibilityMap)),
                    String.valueOf(this.columnNameVisibilityMap.size())));

            AssetConfigurationUi.this.columnVisibilityModal.hide();

        });

        this.btnCancelSetColumnVisibility.addClickHandler(event -> {

        });
    }

    private void saveColumnVisibility() {
        this.localStorage.setItem(COLUMN_VISIBILITY_SETTINGS_KEY + this.model.getAssetPid(),
                this.columnNameVisibilityMap.toString());
    }

    private void loadColumnVisibilityMap() {
        String columnVisibilitySettings = this.localStorage
                .getItem(COLUMN_VISIBILITY_SETTINGS_KEY + this.model.getAssetPid());
        if (columnVisibilitySettings != null) {
            this.columnNameVisibilityMap = ColumnVisibilityMap.fromString(columnVisibilitySettings);
        }
    }

    private boolean validateColumnVisibility(Map<String, Boolean> columnVisibilityModel) {
        return totalEnabledColumns(columnVisibilityModel) > 0;
    }

    private long totalEnabledColumns(Map<?, ?> columnVisibilityModel) {
        return columnVisibilityModel.values().stream().filter(Boolean.TRUE::equals).count();
    }

    private void handleChannelTableVisibility() {
        final boolean isVisible = !this.model.getChannels().isEmpty();
        this.channelTable.setVisible(isVisible);
        this.emptyTableLabel.setVisible(!isVisible);
    }

    private ValidationData validateChannelName(final String channelName) {
        ValidationData result = new ValidationData();

        if (channelName.isEmpty()) {
            result.setInvalid(true);
            result.setValue(MSGS.wiresChannelNameEmpty());
            return result;
        }

        final String prohibitedChars = AssetConstants.CHANNEL_NAME_PROHIBITED_CHARS.value();

        for (int i = 0; i < prohibitedChars.length(); i++) {
            final char prohibitedChar = prohibitedChars.charAt(i);
            if (channelName.indexOf(prohibitedChar) != -1) {
                result.setInvalid(true);
                result.setValue(MSGS.wiresChannelNameInvalidCharacters() + " \'" + prohibitedChar + '\'');
                return result;
            }
        }

        if (channelName.indexOf(' ') != -1) {
            result.setInvalid(true);
            result.setValue(MSGS.wiresChannelNameNoSpaces());
            return result;
        }

        if (this.model.getChannelNames().contains(channelName)) {
            result.setInvalid(true);
            result.setValue(MSGS.wiresChannelNameAlreadyPresent());
            return result;
        }

        result.setInvalid(false);
        return result;
    }

    private String getNewChannelName() {
        int suffix = 1;
        String result = null;
        while (this.model.getChannelNames().contains(result = MSGS.wiresChannel() + suffix)) {
            suffix++;
        }
        return result;
    }

    @Override
    public void setListener(HasConfiguration.Listener listener) {
        this.listener = listener;
        listener.onConfigurationChanged(this);
    }

    public Widget getAssociatedView() {
        return this.associatedView;
    }

    protected void updateNonChannelFields() {
        for (Widget w : this.fields) {
            if (w instanceof FormGroup) {
                FormGroup fg = (FormGroup) w;
                fillUpdatedConfiguration(fg);
            }
        }
    }

    @Override
    public GwtConfigComponent getConfiguration() {
        updateNonChannelFields();
        return this.model.getConfiguration();
    }

    @Override
    public boolean isDirty() {
        return this.dirty;
    }

    @Override
    public boolean isValid() {
        return this.invalidParameters.isEmpty() && super.isValid();
    }

    @Override
    public void clearDirtyState() {
        setDirty(false);
    }

    @Override
    public void markAsDirty() {
        setDirty(true);
    }

    private void downloadChannels() {
        final GwtConfigComponent configuration = this.model.getConfiguration();
        final String driverPid = configuration.getParameterValue(AssetConstants.ASSET_DRIVER_PROP.value());

        RequestQueue.submit(c -> this.gwtXSRFService
                .generateSecurityToken(c.callback(token -> this.gwtAssetService.convertToCsv(token, driverPid,
                        configuration, c.callback(id -> this.gwtXSRFService.generateSecurityToken(c.callback(token2 -> {
                            final StringBuilder sbUrl = new StringBuilder();
                            sbUrl.append("/assetsUpDownload?assetPid=").append(this.model.getAssetPid()).append("&id=")
                                    .append(id);
                            DownloadHelper.instance().startDownload(token2, sbUrl.toString());
                        })))))));
    }

    private void uploadAndApply() {
        this.uploadModal.show();
        this.uploadModal.setTitle(MSGS.upload());
        this.uploadForm.setEncoding(com.google.gwt.user.client.ui.FormPanel.ENCODING_MULTIPART);
        this.uploadForm.setMethod(com.google.gwt.user.client.ui.FormPanel.METHOD_POST);
        this.uploadForm.setAction(SERVLET_URL);

        this.filePath.setName("uploadedFile");

        this.xsrfTokenField.setID("xsrfToken");
        this.xsrfTokenField.setName("xsrfToken");
        this.xsrfTokenField.setValue("");

        this.assetPidField.setID("assetPid");
        this.assetPidField.setName("assetPid");
        this.assetPidField.setValue("");

        this.driverPidField.setID("driverPid");
        this.driverPidField.setName("driverPid");
        this.driverPidField.setValue("");

        this.appendCheck.setName("appendCheck");
        this.appendCheck.setValue(false);
        this.appendCheckField.setID("doReplace");
        this.appendCheckField.setName("doReplace");
        this.appendCheckField.setValue("");

        this.emptyStringCheck.setName("emptyStringCheck");
        this.emptyStringCheck.setValue(false);
        this.emptyStringCheckField.setID("doEmptyStringConversion");
        this.emptyStringCheckField.setName("doEmptyStringConversion");
        this.emptyStringCheckField.setValue("");

    }

    @Override
    public String getComponentId() {
        return this.model.getAssetPid();
    }

    private static String toChannelPropertyName(String propertyId) {
        return propertyId.substring(propertyId.lastIndexOf(AssetConstants.CHANNEL_DEFAULT_PROPERTY_PREFIX.value()) + 1);
    }

    private static class ColumnVisibilityMap extends HashMap<String, Boolean> {

        private static final long serialVersionUID = 1L;

        @Override
        public String toString() {
            return this.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(";"));
        }

        public static ColumnVisibilityMap fromString(String text) {
            ColumnVisibilityMap cv = new ColumnVisibilityMap();
            if (text != null && !text.isEmpty()) {

                String[] settings = text.split(";");
                for (String setting : settings) {
                    String[] parts = setting.split("=");
                    if (parts.length == 2) {
                        cv.put(parts[0], Boolean.parseBoolean(parts[1]));
                    }
                }
            }

            return cv;
        }
    }

    private static class ChannelColumn extends Column<ChannelModel, String> {

        private final GwtConfigParameter param;

        public ChannelColumn(final Cell<String> cell, final GwtConfigParameter param) {
            super(cell);
            this.param = param;
        }

        @Override
        public String getValue(final ChannelModel object) {
            String result = object.getValue(this.param.getId());
            if (result != null) {
                return result;
            }
            return this.param.isRequired() ? this.param.getDefault() : null;
        }

        @Override
        public String getCellStyleNames(Context context, ChannelModel object) {
            if (!object.isValid(this.param.getId())) {
                return "config-cell-not-valid";
            } else {
                return "";
            }
        }
    }
}
