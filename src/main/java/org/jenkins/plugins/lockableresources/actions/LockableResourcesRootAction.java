/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.actions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.markup.MarkupFormatter;
import hudson.model.Api;
import hudson.model.Descriptor;
import hudson.model.RootAction;
import hudson.model.Run;
import hudson.security.AccessDeniedException3;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import io.jenkins.plugins.datatables.AsyncTableContentProvider;
import io.jenkins.plugins.datatables.DetailedCell;
import io.jenkins.plugins.datatables.TableColumn;
import io.jenkins.plugins.datatables.TableConfiguration;
import io.jenkins.plugins.datatables.TableModel;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.Messages;
import org.jenkins.plugins.lockableresources.queue.LockableResourcesStruct;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
@ExportedBean
public class LockableResourcesRootAction implements RootAction, AsyncTableContentProvider {

    private static final Logger LOGGER = Logger.getLogger(LockableResourcesRootAction.class.getName());

    public static final PermissionGroup PERMISSIONS_GROUP = new PermissionGroup(
            LockableResourcesManager.class, Messages._LockableResourcesRootAction_PermissionGroup());
    public static final Permission UNLOCK = new Permission(
            PERMISSIONS_GROUP,
            "Unlock",
            Messages._LockableResourcesRootAction_UnlockPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission RESERVE = new Permission(
            PERMISSIONS_GROUP,
            "Reserve",
            Messages._LockableResourcesRootAction_ReservePermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission STEAL = new Permission(
            PERMISSIONS_GROUP,
            "Steal",
            Messages._LockableResourcesRootAction_StealPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission VIEW = new Permission(
            PERMISSIONS_GROUP,
            "View",
            Messages._LockableResourcesRootAction_ViewPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);
    public static final Permission QUEUE = new Permission(
            PERMISSIONS_GROUP,
            "Queue",
            Messages._LockableResourcesRootAction_QueueChangeOrderPermission_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    public static final String ICON = "symbol-lock-closed";

    @Override
    public String getIconFileName() {
        return Jenkins.get().hasPermission(VIEW) ? ICON : null;
    }

    public Api getApi() {
        return new Api(this);
    }

    @CheckForNull
    public String getUserName() {
        return LockableResource.getUserName();
    }

    @Override
    public String getDisplayName() {
        return Messages.LockableResourcesRootAction_PermissionGroup();
    }

    @Override
    public String getUrlName() {
        return "lockable-resources";
    }

    private static final String RESOURCES_TABLE_ID = "lockable-resources";

    @Restricted(NoExternalUse.class) // used by jelly
    public Summary getSummary() {
        Jenkins.get().checkPermission(VIEW);

        int locked = 0;
        int reserved = 0;
        int queued = 0;
        int free = 0;
        int total = 0;

        for (LockableResource r : LockableResourcesManager.get().getReadOnlyResources()) {
            total++;
            if (r.getReservedBy() != null) {
                reserved++;
            } else if (r.isLocked()) {
                locked++;
            } else if (r.isQueued()) {
                queued++;
            } else {
                free++;
            }
        }

        int queueItems = 0;
        for (QueuedContextStruct context : LockableResourcesManager.get().getCurrentQueuedContext()) {
            queueItems += context.getResources().size();
        }

        return new Summary(total, locked, reserved, queued, free, queueItems);
    }

    @Restricted(NoExternalUse.class)
    public static final class Summary {
        private final int total;
        private final int locked;
        private final int reserved;
        private final int queued;
        private final int free;
        private final int queueItems;

        Summary(
                final int total,
                final int locked,
                final int reserved,
                final int queued,
                final int free,
                final int queueItems) {
            this.total = total;
            this.locked = locked;
            this.reserved = reserved;
            this.queued = queued;
            this.free = free;
            this.queueItems = queueItems;
        }

        public int getTotal() {
            return total;
        }

        public int getLocked() {
            return locked;
        }

        public int getReserved() {
            return reserved;
        }

        public int getQueued() {
            return queued;
        }

        public int getFree() {
            return free;
        }

        public int getQueueItems() {
            return queueItems;
        }
    }

    @Restricted(NoExternalUse.class) // used by jelly
    public TableModel getResourcesTableModel() {
        return new ResourcesTableModel(getLocale());
    }

    @Override
    @JavaScriptMethod
    public TableModel getTableModel(final String tableId) {
        Jenkins.get().checkPermission(VIEW);
        if (RESOURCES_TABLE_ID.equals(tableId)) {
            return getResourcesTableModel();
        }
        throw new IllegalArgumentException("Unknown table model: " + tableId);
    }

    @Override
    @JavaScriptMethod
    public String getTableRows(final String tableId) {
        Jenkins.get().checkPermission(VIEW);
        List<Object> rows = getTableModel(tableId).getRows();
        try {
            return new ObjectMapper().writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(String.format("Can't convert table rows '%s' to JSON object", rows), e);
        }
    }

    private static Locale getLocale() {
        StaplerRequest2 current = Stapler.getCurrentRequest2();
        return current != null ? current.getLocale() : Locale.getDefault();
    }

    private static ResourceBundle getTableResourcesBundle(final Locale locale) {
        return ResourceBundle.getBundle(
                "org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction.tableResources.table",
                locale,
                LockableResourcesRootAction.class.getClassLoader());
    }

    private static String tr(final ResourceBundle bundle, final String key, final Object... args) {
        String pattern = bundle.getString(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }

    private static final class ResourcesTableModel extends TableModel {
        private final Locale locale;
        private final ResourceBundle bundle;

        ResourcesTableModel(final Locale locale) {
            this.locale = locale;
            this.bundle = getTableResourcesBundle(locale);
        }

        @Override
        public String getId() {
            return RESOURCES_TABLE_ID;
        }

        @Override
        public List<TableColumn> getColumns() {
            return List.of(
                    new TableColumn.ColumnBuilder()
                    .withHeaderLabel(tr(bundle, "resources.table.column.select"))
                            .withDataPropertyKey("select")
                            .withType(TableColumn.ColumnType.STRING)
                            .withHeaderClass(TableColumn.ColumnCss.NO_SORT)
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.index"))
                            .withDataPropertyKey("index")
                            .withType(TableColumn.ColumnType.HTML_NUMBER)
                            .withDetailedCell()
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.resource"))
                            .withDataPropertyKey("resource")
                            .withType(TableColumn.ColumnType.STRING)
                            .withDetailedCell()
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.status"))
                            .withDataPropertyKey("status")
                            .withType(TableColumn.ColumnType.STRING)
                            .withDetailedCell()
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.timestamp"))
                            .withDataPropertyKey("timestamp")
                            .withType(TableColumn.ColumnType.DATE)
                            .withDetailedCell()
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.labels"))
                            .withDataPropertyKey("labels")
                            .withType(TableColumn.ColumnType.STRING)
                            .withDetailedCell()
                            .build(),
                    new TableColumn.ColumnBuilder()
                            .withHeaderLabel(tr(bundle, "resources.table.column.properties"))
                            .withDataPropertyKey("properties")
                            .withType(TableColumn.ColumnType.STRING)
                            .withHeaderClass(TableColumn.ColumnCss.HIDDEN)
                            .build());
        }

        @Override
        public TableConfiguration getTableConfiguration() {
            // Ensure required JS/CSS adjuncts are included by the dt:table tag.
            // The full table config is still provided by getTableConfigurationDefinition().
            return new TableConfiguration().buttons("colvis").stateSave();
        }

        @Override
        public String getTableConfigurationDefinition() {
            // Keep behavior aligned with the previous Jelly-based configuration, but rely on
            // data-tables-api defaults for layout and column behaviors.
            return "{\n"
                    + "  \"order\": [[1, \"asc\"]],\n"
                    + "  \"stateSave\": true,\n"
                    + "  \"buttons\": [{\"extend\": \"colvis\", \"text\": \""
                    + Util.escape(tr(bundle, "table.buttons.columns")).replace("\"", "\\\"")
                    + "\", \"columns\": [1,2,3,4,5]}],\n"
                    + "  \"language\": { \"emptyTable\": \""
                    + Util.escape(tr(bundle, "table.empty")).replace("\"", "\\\"") + "\" },\n"
                    + "  \"lengthMenu\": [[10, 25, 50, 100, -1], [10, 25, 50, 100, \""
                    + Util.escape(tr(bundle, "table.settings.page.length.all")).replace("\"", "\\\"") + "\"]]\n"
                    + "}";
        }

        @Override
        public List<Object> getRows() {
            List<Object> rows = new ArrayList<>();

            DateFormat dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
            MarkupFormatter markupFormatter = Jenkins.get().getMarkupFormatter();

            int index = 0;
            for (LockableResource resource : LockableResourcesManager.get().getReadOnlyResources()) {
                index++;
                Map<String, Object> row = new LinkedHashMap<>();

                row.put(
                        "select",
                        "<input type=\"checkbox\" class=\"lockable-resources-select\" data-resource-name=\""
                                + Util.escape(resource.getName())
                                + "\" />");

                String propertiesHtml = renderProperties(resource);
                String details = TableColumn.renderDetailsColumn(propertiesHtml);
                row.put("index", new DetailedCell<>(details + " " + index, index));

                row.put(
                        "resource",
                        new DetailedCell<>(renderResourceCell(resource, markupFormatter), resource.getName()));
                row.put("status", new DetailedCell<>(renderStatusCell(resource), renderStatusSortKey(resource)));

                Date reservedTimestamp = resource.getReservedTimestamp();
                if (reservedTimestamp != null) {
                    row.put(
                            "timestamp",
                            new DetailedCell<>(dateTime.format(reservedTimestamp), reservedTimestamp.getTime()));
                } else {
                    row.put("timestamp", new DetailedCell<>("", 0L));
                }

                row.put(
                        "labels",
                        new DetailedCell<>(renderLabels(resource), String.join(",", resource.getLabelsAsList())));
                row.put("properties", propertiesHtml);

                rows.add(row);
            }

            return rows;
        }

        private String renderResourceCell(final LockableResource resource, final MarkupFormatter markupFormatter) {
            StringBuilder html = new StringBuilder();

            String resourceName = resource.getName();
            String escapedName = Util.escape(resourceName);

            html.append("<div class=\"row justify-content-end\">");
            html.append("<div class=\"col-auto\"><strong>").append(escapedName).append("</strong></div>");
            html.append("<div class=\"col\">");
            if (resource.isEphemeral()) {
                html.append("<span class=\"static-label\">")
                        .append(Util.escape(tr(bundle, "resources.ephemeral")))
                        .append("</span>");
            }
            html.append("</div>");

            if (Jenkins.get().hasPermission(RESERVE)) {
                html.append("<div class=\"col-auto jenkins-!-margin-right-2\">")
                        .append(
                                "<a class=\"jenkins-table__link lockable-resources-replace-note\" data-resource-name=\"")
                        .append(Util.escape(resourceName))
                        .append("\" href=\"editNote\">")
                        .append(Util.escape(tr(bundle, "btn.editNote")))
                        .append("</a></div>");
            }
            html.append("</div>");

            if (resource.getDescription() != null && !resource.getDescription().isEmpty()) {
                html.append("<div class=\"row\"><div class=\"col\">")
                        .append(Util.escape(resource.getDescription()))
                        .append("</div></div>");
            }

            html.append("<div class=\"row\"><div id=\"note-")
                    .append(Util.escape(resourceName))
                    .append("\">");
            if (resource.getNote() != null && !resource.getNote().isEmpty()) {
                html.append("<div class=\"note-wrapper jenkins-!-padding-2 jenkins-!-margin-right-1 overflow-auto\">");
                html.append(translateNote(markupFormatter, resource.getNote()));
                html.append("</div>");
            }
            html.append("</div></div>");

            return html.toString();
        }

        private static String translateNote(final MarkupFormatter markupFormatter, final String note) {
            try {
                StringWriter writer = new StringWriter();
                markupFormatter.translate(note, writer);
                return writer.toString();
            } catch (IOException e) {
                // Fallback: show escaped raw note if translation fails.
                return Util.escape(note);
            }
        }

        private String renderStatusCell(final LockableResource resource) {
            StringBuilder html = new StringBuilder();

            if (resource.getReservedBy() != null) {
                html.append(tr(bundle, "resource.status.reservedBy", Util.escape(resource.getReservedBy())));
                appendReason(html, resource);
            } else if (resource.isLocked()) {
                Run<?, ?> build = resource.getBuild();
                if (build != null) {
                    html.append(tr(
                            bundle,
                            "resource.status.locked",
                            Util.escape("/" + build.getUrl()),
                            Util.escape(build.getFullDisplayName())));
                } else {
                    html.append(tr(bundle, "resource.status.locked", "#", "N/A"));
                }
                appendReason(html, resource);
            } else if (resource.isQueued()) {
                html.append(tr(
                        bundle,
                        "resource.status.queuedBy",
                        Util.escape(resource.getQueueItemProject()),
                        Util.escape(String.valueOf(resource.getQueueItemId()))));
            } else {
                html.append(tr(bundle, "resource.status.free"));
            }

            Date reservedTimestamp = resource.getReservedTimestamp();
            if (reservedTimestamp != null) {
                long delta = Math.max(0L, System.currentTimeMillis() - reservedTimestamp.getTime());
                String ago = tr(bundle, "ago", Util.getTimeSpanString(delta));
                html.append("<br />").append(Util.escape(ago));
            }

            return html.toString();
        }

        private void appendReason(final StringBuilder html, final LockableResource resource) {
            if (resource.getLockReason() != null && !resource.getLockReason().isEmpty()) {
                html.append("<br/>")
                        .append("<span class=\"jenkins-!-font-weight-normal\">")
                        .append(Util.escape(tr(bundle, "resource.status.reason")))
                        .append(": ")
                        .append(Util.escape(resource.getLockReason()))
                        .append("</span>");
            }
        }

        private static int renderStatusSortKey(final LockableResource resource) {
            if (resource.getReservedBy() != null) {
                return 3;
            }
            if (resource.isLocked()) {
                return 2;
            }
            if (resource.isQueued()) {
                return 1;
            }
            return 0;
        }

        private String renderLabels(final LockableResource resource) {
            StringBuilder html = new StringBuilder();
            for (String label : resource.getLabelsAsList()) {
                if (label == null || label.isEmpty()) {
                    continue;
                }
                html.append("<a class=\"jenkins-table__link model-link\" href=\"/label/")
                        .append(Util.rawEncode(label))
                        .append("\">")
                        .append(Util.escape(label))
                        .append("</a>");
            }
            return html.toString();
        }

        private String renderProperties(final LockableResource resource) {
            if (resource.getProperties() == null || resource.getProperties().isEmpty()) {
                return "";
            }

            StringBuilder html = new StringBuilder();
            html.append(
                    "<div class=\"table-responsive\"><table class=\"jenkins-table jenkins-!-margin-0 table-properties\"><tbody>");
            resource.getProperties().forEach(property -> {
                html.append("<tr><td>")
                        .append(Util.escape(property.getName()))
                        .append("</td><td>")
                        .append(Util.escape(property.getValue()))
                        .append("</td></tr>");
            });
            html.append("</tbody></table></div>");
            return html.toString();
        }

    }

    // ---------------------------------------------------------------------------
    /**
     * Get a list of resources
     *
     * @return All resources.
     */
    @Exported
    @Restricted(NoExternalUse.class) // used by jelly
    public List<LockableResource> getResources() {
        return LockableResourcesManager.get().getReadOnlyResources();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get a list of all labels
     *
     * @return All possible labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    public LinkedHashMap<String, LockableResourcesLabel> getLabelsList() {
        LinkedHashMap<String, LockableResourcesLabel> map = new LinkedHashMap<>();

        for (LockableResource r : LockableResourcesManager.get().getReadOnlyResources()) {
            if (r == null || r.getName().isEmpty()) {
                continue; // defensive, shall never happens, but ...
            }
            List<String> assignedLabels = r.getLabelsAsList();
            if (assignedLabels.isEmpty()) {
                continue;
            }

            for (String labelString : assignedLabels) {
                if (labelString == null || labelString.isEmpty()) {
                    continue; // defensive, shall never happens, but ...
                }
                LockableResourcesLabel label = map.get(labelString);
                if (label == null) {
                    label = new LockableResourcesLabel(labelString);
                }

                label.update(r);

                map.put(labelString, label);
            }
        }

        return map;
    }

    // ---------------------------------------------------------------------------
    public static class LockableResourcesLabel {
        String name;
        int free;
        int assigned;

        // -------------------------------------------------------------------------
        public LockableResourcesLabel(String _name) {
            this.name = _name;
            this.free = 0;
            this.assigned = 0;
        }

        // -------------------------------------------------------------------------
        public void update(LockableResource resource) {
            this.assigned++;
            if (resource.isFree()) free++;
        }

        // -------------------------------------------------------------------------
        public String getName() {
            return this.name;
        }

        // -------------------------------------------------------------------------
        public int getFree() {
            return this.free;
        }

        // -------------------------------------------------------------------------
        public int getAssigned() {
            return this.assigned;
        }

        // -------------------------------------------------------------------------
        public int getPercentage() {
            if (this.assigned == 0) {
                return this.assigned;
            }
            return (int) ((double) this.free / (double) this.assigned * 100);
        }
    }

    // ---------------------------------------------------------------------------
    // used by by
    // src\main\resources\org\jenkins\plugins\lockableresources\actions\LockableResourcesRootAction\tableResources\table.jelly
    @Restricted(NoExternalUse.class)
    public LockableResource getResource(final String resourceName) {
        return LockableResourcesManager.get().fromName(resourceName);
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of free resources assigned to given *labelString*
     *
     * @param labelString Label to search.
     * @return Amount of free labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getFreeResourceAmount(final String labelString) {
        this.informPerformanceIssue();
        LockableResourcesLabel label = this.getLabelsList().get(labelString);
        return (label == null) ? 0 : label.getFree();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get percentage (0-100) usage of resources assigned to given *labelString*
     *
     * <p>Used by {@code actions/LockableResourcesRootAction/index.jelly}
     *
     * @since 2.19
     * @param labelString Label to search.
     * @return Percentage usages of *labelString* around all resources
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getFreeResourcePercentage(final String labelString) {
        this.informPerformanceIssue();
        LockableResourcesLabel label = this.getLabelsList().get(labelString);
        return (label == null) ? 0 : label.getPercentage();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get all existing labels as list.
     *
     * @return All possible labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public Set<String> getAllLabels() {
        this.informPerformanceIssue();
        return LockableResourcesManager.get().getAllLabels();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of all labels.
     *
     * @return Amount of all labels.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getNumberOfAllLabels() {
        this.informPerformanceIssue();
        return this.getLabelsList().size();
    }

    // ---------------------------------------------------------------------------
    /**
     * Get amount of resources assigned to given *labelString*
     *
     * <p>Used by {@code actions/LockableResourcesRootAction/index.jelly}
     *
     * @param labelString Label to search.
     * @return Amount of assigned resources.
     */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public int getAssignedResourceAmount(String labelString) {
        this.informPerformanceIssue();
        return LockableResourcesManager.get().getResourcesWithLabel(labelString).size();
    }

    // ---------------------------------------------------------------------------
    private void informPerformanceIssue() {
        String method = Thread.currentThread().getStackTrace()[2].getMethodName();
        StringBuilder buf = new StringBuilder();
        for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
            buf.append("\n").append(st);
        }
        LOGGER.warning("lockable-resources-plugin: The method "
                + method
                + " has been deprecated due performance issues. When you see this message, please inform plugin developers:"
                + buf);
    }

    // ---------------------------------------------------------------------------
    @Restricted(NoExternalUse.class) // used by jelly
    public Queue getQueue() throws Descriptor.FormException {
        List<QueuedContextStruct> currentQueueContext =
                List.copyOf(LockableResourcesManager.get().getCurrentQueuedContext());
        Queue queue = new Queue();

        for (QueuedContextStruct context : currentQueueContext) {
            for (LockableResourcesStruct resourceStruct : context.getResources()) {
                queue.add(resourceStruct, context);
            }
        }

        return queue;
    }

    // ---------------------------------------------------------------------------
    public static class Queue {

        List<QueueStruct> queue;
        QueueStruct oldest;

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public Queue() {
            this.queue = new ArrayList<>();
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public void add(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context)
                throws Descriptor.FormException {
            QueueStruct queueStruct = new QueueStruct(resourceStruct, context);
            queue.add(queueStruct);
            if (resourceStruct.queuedAt == 0) {
                // Older versions of this plugin might miss this information.
                // Therefore skip it here.
                return;
            }
            if (oldest == null || oldest.getQueuedAt() > queueStruct.getQueuedAt()) {
                oldest = queueStruct;
            }
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public List<QueueStruct> getAll() {
            return Collections.unmodifiableList(this.queue);
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public QueueStruct getOldest() {
            return this.oldest;
        }

        // -------------------------------------------------------------------------
        @Restricted(NoExternalUse.class) // used by jelly
        public static class QueueStruct {
            List<LockableResource> requiredResources;
            String requiredLabel;
            String groovyScript;
            String requiredNumber;
            long queuedAt = 0;
            int priority = 0;
            String id = null;
            Run<?, ?> build;

            public QueueStruct(final LockableResourcesStruct resourceStruct, final QueuedContextStruct context)
                    throws Descriptor.FormException {
                this.requiredResources = resourceStruct.required;
                this.requiredLabel = resourceStruct.label;
                this.requiredNumber = resourceStruct.requiredNumber;
                this.queuedAt = resourceStruct.queuedAt;
                this.build = context.getBuild();
                this.priority = context.getPriority();
                this.id = context.getId();

                final SecureGroovyScript systemGroovyScript = resourceStruct.getResourceMatchScript();
                if (systemGroovyScript != null) {
                    this.groovyScript = systemGroovyScript.getScript();
                }
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public List<LockableResource> getRequiredResources() {
                return this.requiredResources;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getRequiredLabel() {
                return this.requiredLabel == null ? "N/A" : this.requiredLabel;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getRequiredNumber() {
                return this.requiredNumber == null ? "0" : this.requiredNumber;
            }

            // -----------------------------------------------------------------------
            /** */
            @NonNull
            @Restricted(NoExternalUse.class) // used by jelly
            public String getGroovyScript() {
                return this.groovyScript == null ? "N/A" : this.groovyScript;
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public Run<?, ?> getBuild() {
                return this.build;
            }

            // -----------------------------------------------------------------------
            /** */
            @Restricted(NoExternalUse.class) // used by jelly
            public long getQueuedAt() {
                return this.queuedAt;
            }

            // -----------------------------------------------------------------------
            /** Check if the queue takes too long. At the moment "too long" means over 1 hour. */
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean takeTooLong() {
                return (new Date().getTime() - this.queuedAt) > 3600000L;
            }

            // -----------------------------------------------------------------------
            /** Returns timestamp when the resource has been added into queue. */
            @Restricted(NoExternalUse.class) // used by jelly
            public Date getQueuedTimestamp() {
                return new Date(this.queuedAt);
            }

            // -----------------------------------------------------------------------
            /** Returns queue priority. */
            @Restricted(NoExternalUse.class) // used by jelly
            public int getPriority() {
                if (this.id == null) {
                    // defensive
                    // in case of jenkins update from older version and you have some queue
                    // might happens, that there are no priority set
                    return 0;
                }
                return this.priority;
            }

            // -----------------------------------------------------------------------
            /** Returns queue ID. */
            @Restricted(NoExternalUse.class)
            public String getId() {
                if (this.id == null) {
                    // defensive
                    // in case of jenkins update from older version and you have some queue
                    // might happens, that there are no priority set
                    return "NN";
                }
                return this.id;
            }

            @Restricted(NoExternalUse.class) // used by jelly
            public boolean resourcesMatch() {
                return (requiredResources != null && requiredResources.size() > 0);
            }

            // -----------------------------------------------------------------------
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean labelsMatch() {
                return (requiredLabel != null);
            }

            // -----------------------------------------------------------------------
            @Restricted(NoExternalUse.class) // used by jelly
            public boolean scriptMatch() {
                return (groovyScript != null && !groovyScript.isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------------------
    /** Returns current queue */
    @Restricted(NoExternalUse.class) // used by jelly
    @Deprecated // slow down plugin execution due concurrent modification checks
    public List<QueuedContextStruct> getCurrentQueuedContext() {
        return LockableResourcesManager.get().getCurrentQueuedContext();
    }

    // ---------------------------------------------------------------------------
    /** Returns current queue */
    @Restricted(NoExternalUse.class) // used by jelly
    @CheckForNull
    @Deprecated // slow down plugin execution due concurrent modification checks
    public LockableResourcesStruct getOldestQueue() {
        LockableResourcesStruct oldest = null;
        for (QueuedContextStruct context : this.getCurrentQueuedContext()) {
            for (LockableResourcesStruct resourceStruct : context.getResources()) {
                if (resourceStruct.queuedAt == 0) {
                    // Older versions of this plugin might miss this information.
                    // Therefore skip it here.
                    continue;
                }
                if (oldest == null || oldest.queuedAt > resourceStruct.queuedAt) {
                    oldest = resourceStruct;
                }
            }
        }
        return oldest;
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doUnlock(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        LockableResourcesManager.get().unlockResources(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));

        LOGGER.info("doReserve called for resources=" + LockableResourcesManager.getResourcesNames(resources)
                + " reason='" + reason + "' fromIP=" + req.getRemoteAddr());

        String userName = getUserName();
        if (userName == null) {
            LOGGER.warning("doReserve: userName is null (unauthenticated?) for resources="
                    + LockableResourcesManager.getResourcesNames(resources));
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        boolean ok = LockableResourcesManager.get().reserve(resources, userName, reason);
        if (!ok) {
            LOGGER.info("doReserve failed - resource already locked: "
                    + LockableResourcesManager.getResourcesNames(resources));
            rsp.sendError(
                    423, Messages.error_resourceAlreadyLocked(LockableResourcesManager.getResourcesNames(resources)));
            return;
        }
        LOGGER.info("doReserve succeeded for user='" + userName + "' resources="
                + LockableResourcesManager.getResourcesNames(resources));
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doSteal(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(STEAL);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String reason = Util.fixEmptyAndTrim(req.getParameter("reason"));

        String userName = getUserName();
        if (userName == null) {
            rsp.sendError(401, Messages.error_notAuthenticated());
            return;
        }

        LockableResourcesManager.get().steal(resources, userName, reason);
        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReassign(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(STEAL);

        String userName = getUserName();
        if (userName == null) {
            // defensive: this can not happens because we check you permissions few lines before
            // therefore you must be logged in
            throw new AccessDeniedException3(Jenkins.getAuthentication2(), STEAL);
        }

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        for (LockableResource resource : resources) {
            if (userName.equals(resource.getReservedBy())) {
                // Can not achieve much by re-assigning the
                // resource I already hold to myself again,
                // that would just burn the compute resources.
                // ...unless something catches the event? (TODO?)
                return;
            }
        }

        LockableResourcesManager.get().reassign(resources, userName);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doUnreserve(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        String userName = getUserName();
        for (LockableResource resource : resources) {
            if ((userName == null || !userName.equals(resource.getReservedBy()))
                    && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                throw new AccessDeniedException3(Jenkins.getAuthentication2(), RESERVE);
            }
        }

        LockableResourcesManager.get().unreserve(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doReset(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(UNLOCK);
        // Should this also be permitted by "STEAL"?..

        List<LockableResource> resources = this.getResourcesFromRequest(req, rsp);
        if (resources == null) {
            return;
        }

        LockableResourcesManager.get().reset(resources);

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    @RequirePOST
    public void doSaveNote(final StaplerRequest2 req, final StaplerResponse2 rsp) throws IOException, ServletException {
        Jenkins.get().checkPermission(RESERVE);

        String resourceName = req.getParameter("resource");
        if (resourceName == null) {
            resourceName = req.getParameter("resourceName");
        }

        final LockableResource resource = getResource(resourceName);
        if (resource == null) {
            rsp.sendError(404, Messages.error_resourceDoesNotExist(resourceName));
        } else {
            String resourceNote = req.getParameter("note");
            if (resourceNote == null) {
                resourceNote = req.getParameter("resourceNote");
            }
            resource.setNote(resourceNote);
            LockableResourcesManager.get().save();

            rsp.forwardToPreviousPage(req);
        }
    }

    // ---------------------------------------------------------------------------
    /** Change queue order (item position) */
    @Restricted(NoExternalUse.class) // used by jelly
    @RequirePOST
    public void doChangeQueueOrder(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        Jenkins.get().checkPermission(QUEUE);

        final String queueId = req.getParameter("id");
        final String newIndexStr = req.getParameter("index");

        LOGGER.fine("doChangeQueueOrder, id: " + queueId + " newIndexStr: " + newIndexStr);

        final int newIndex;
        try {
            newIndex = Integer.parseInt(newIndexStr);
        } catch (NumberFormatException e) {
            rsp.sendError(423, Messages.error_isNotANumber(newIndexStr));
            return;
        }

        try {
            LockableResourcesManager.get().changeQueueOrder(queueId, newIndex - 1);
        } catch (IOException e) {
            rsp.sendError(423, e.toString().replace("java.io.IOException: ", ""));
            return;
        }

        rsp.forwardToPreviousPage(req);
    }

    // ---------------------------------------------------------------------------
    private List<LockableResource> getResourcesFromRequest(final StaplerRequest2 req, final StaplerResponse2 rsp)
            throws IOException, ServletException {
        // todo, when you try to improve the API to use multiple resources (a list instead of single
        // one)
        // this will be the best place to change it. Probably it will be enough to add a code piece here
        // like req.getParameter("resources"); And split the content by some delimiter like ' ' (space)
        String name = req.getParameter("resource");
        LockableResource r = LockableResourcesManager.get().fromName(name);
        if (r == null) {
            rsp.sendError(404, Messages.error_resourceDoesNotExist(name));
            return null;
        }

        List<LockableResource> resources = new ArrayList<>();
        resources.add(r);
        return resources;
    }
}
