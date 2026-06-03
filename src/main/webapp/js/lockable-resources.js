// SPDX-License-Identifier: MIT
// Copyright (c) 2020, Tobias Gruetzmacher

function changeQueueOrder(queueId) {

  dialog
    .prompt(i18n("queue-title", queueId), {
      message: i18n("queue-message", queueId),
      minWidth: "450px",
      maxWidth: "600px"
    })
    .then(
      (newPosition) => {
        fetch("changeQueueOrder?id=" + queueId + "&index=" + newPosition,
          {
            method: "post",
            headers: crumb.wrap({}),
          },
        ).then((rsp) => {
          showResponse(rsp, i18n("queue-on-success", queueId), i18n("queue-on-fail", queueId));
        });
      }
    );
}

function showResponse(rsp, okText, errorText) {
  rsp.text().then((responseText) => {
    if (!rsp.ok) {
      let parsed = new DOMParser().parseFromString(responseText, 'text/html');
      dialog.alert(errorText, {
        message: parsed.title
      });
    } else {
      notificationBar.show(okText, notificationBar.SUCCESS);
      // Preserve current tab on reload
      var activeTab = document.querySelector(".lr-tab-button--active");
      if (activeTab && !window.location.hash) {
        window.location.hash = activeTab.dataset.lrTab;
      }
      window.location.reload();
    }
  });
}

function createPropertyRow(container, name, value) {
  const row = document.createElement("div");
  row.className = "lr-property-row";
  row.innerHTML = '<input type="text" class="jenkins-input lr-prop-name" placeholder="Name" />'
    + '<input type="text" class="jenkins-input lr-prop-value" placeholder="Value" />'
    + '<button type="button" class="jenkins-button jenkins-!-destructive-color lr-remove-property">\u00D7</button>';
  if (name) row.querySelector(".lr-prop-name").value = name;
  if (value) row.querySelector(".lr-prop-value").value = value;
  row.querySelector(".lr-remove-property").addEventListener("click", function () { row.remove(); });
  container.appendChild(row);
}

function collectProperties(content) {
  const properties = [];
  content.querySelectorAll(".lr-property-row").forEach(function (r) {
    const pn = r.querySelector(".lr-prop-name").value.trim();
    const pv = r.querySelector(".lr-prop-value").value.trim();
    if (pn) properties.push({ name: pn, value: pv });
  });
  return properties;
}

function openResourceDialog(content, title, submitLabel, onSubmit, focusField) {
  // Wire up "Add Property" button
  const container = content.querySelector(".lr-properties-container");
  content.querySelector(".lr-add-property-btn").addEventListener("click", function () {
    createPropertyRow(container);
  });

  dialog.modal(content, { title: title, maxWidth: "550px" });

  setTimeout(function () {
    const dlg = document.querySelector("dialog.jenkins-dialog");
    if (!dlg) return;

    const footer = document.createElement("div");
    footer.className = "jenkins-buttons-row jenkins-buttons-row--equal-width";
    footer.style.marginTop = "1rem";
    const submitBtn = document.createElement("button");
    submitBtn.className = "jenkins-button jenkins-button--primary";
    submitBtn.textContent = submitLabel;
    footer.appendChild(submitBtn);
    content.appendChild(footer);

    submitBtn.addEventListener("click", function () {
      dlg.dispatchEvent(new Event("cancel"));
      onSubmit();
    });

    const field = content.querySelector("input[name='" + focusField + "']");
    if (field) field.focus();
  }, 50);
}

function showAddResourceDialog() {
  const template = document.getElementById("lr-add-resource-template");
  if (!template) return;
  const content = template.content.firstElementChild.cloneNode(true);

  openResourceDialog(content, i18n("add-resource-dialog-title"), i18n("btn-addResource"), function () {
    const name = content.querySelector("input[name='name']").value.trim();
    if (!name) { content.querySelector("input[name='name']").focus(); return; }
    const body = { name: name };
    const desc = content.querySelector("input[name='description']").value.trim();
    const lbl = content.querySelector("input[name='labels']").value.trim();
    const props = collectProperties(content);
    if (desc) body.description = desc;
    if (lbl) body.labels = lbl;
    if (props.length > 0) body.properties = props;

    fetch("createResource", {
      method: "post",
      headers: crumb.wrap({ "Content-Type": "application/json" }),
      body: JSON.stringify(body),
    }).then(function (rsp) {
      showResponse(rsp, i18n("add-resource-on-success", name), i18n("add-resource-on-fail"));
    });
  }, "name");
}

function editResource(button) {
  const row = button.closest("tr");
  const resourceName = row.getAttribute("data-resource-name");
  const currentDesc = row.getAttribute("data-resource-description") || "";
  const currentLabels = row.getAttribute("data-resource-labels") || "";

  const currentProps = [];
  row.querySelectorAll(".lr-property-pill").forEach(function (pill) {
    const k = pill.querySelector(".lr-property-pill__key");
    const v = pill.querySelector(".lr-property-pill__value");
    if (k) currentProps.push({ name: k.textContent.trim(), value: v ? v.textContent.trim() : "" });
  });

  const template = document.getElementById("lr-add-resource-template");
  if (!template) return;
  const content = template.content.firstElementChild.cloneNode(true);

  const nameInput = content.querySelector("input[name='name']");
  nameInput.value = resourceName;
  nameInput.readOnly = true;
  nameInput.style.opacity = "0.6";
  content.querySelector("input[name='description']").value = currentDesc;
  content.querySelector("input[name='labels']").value = currentLabels;

  const container = content.querySelector(".lr-properties-container");
  currentProps.forEach(function (prop) {
    createPropertyRow(container, prop.name, prop.value);
  });

  openResourceDialog(content, i18n("edit-resource-dialog-title", resourceName), i18n("edit-resource-btn-save"), function () {
    const body = {
      name: resourceName,
      description: content.querySelector("input[name='description']").value.trim(),
      labels: content.querySelector("input[name='labels']").value.trim(),
      properties: collectProperties(content)
    };

    fetch("editResource", {
      method: "post",
      headers: crumb.wrap({ "Content-Type": "application/json" }),
      body: JSON.stringify(body),
    }).then(function (rsp) {
      showResponse(rsp, i18n("edit-resource-on-success", resourceName), i18n("edit-resource-on-fail", resourceName));
    });
  }, "description");
}

function deleteResource(button) {
  const resourceName = button.closest("tr").getAttribute("data-resource-name");

  dialog
    .confirm(i18n("delete-resource-message", resourceName), {
      type: "destructive",
      okText: i18n("delete-resource-title", resourceName)
    })
    .then(function () {
      fetch("deleteResource?resource=" + encodeURIComponent(resourceName), {
        method: "post",
        headers: crumb.wrap({}),
      }).then(function(rsp) {
        showResponse(rsp, i18n("delete-resource-on-success", resourceName), i18n("delete-resource-on-fail", resourceName));
      });
    });
}

function resource_action(button, action) {
  const resourceName = button.closest('tr').getAttribute('data-resource-name');

  if (action === "reserve" || action === "steal") {
    dialog
      .prompt(i18n(action + "-title", resourceName), {
        message: i18n(action + "-message", resourceName),
        minWidth: "450px",
        maxWidth: "600px"
      })
      .then(
        (reason) => {
          const url = action + "?resource=" + encodeURIComponent(resourceName) + "&reason=" + encodeURIComponent(reason || "");
          fetch(url, {
            method: "post",
            headers: crumb.wrap({}),
          }).then((rsp) => {
            showResponse(rsp, i18n("action-on-success", action, resourceName), i18n("action-on-fail", action, resourceName));
            if (!rsp.ok) window.location.reload();
          });
        }
      );
    return;
  }

  fetch(action + "?resource=" + encodeURIComponent(resourceName), {
    method: "post",
    headers: crumb.wrap({}),
  }).then((rsp) => {
    showResponse(rsp, i18n("action-on-success", action, resourceName), i18n("action-on-fail", action, resourceName));
    if (!rsp.ok) window.location.reload();
  });
}

function replaceNote(resourceName) {
  const d = document.getElementById("note-" + resourceName);
  // Toggle: if note form is already open, collapse it
  if (d.dataset.noteOpen === "true") {
    d.innerHTML = d.dataset.noteOriginal || "";
    d.dataset.noteOpen = "false";
    return;
  }
  // Save original content for collapse
  d.dataset.noteOriginal = d.innerHTML;
  d.dataset.noteOpen = "true";
  d.innerHTML = "<div class='spinner-right' style='flex-grow: 1;'>loading...</div>";
  fetch("noteForm", {
    method: "post",
    headers: crumb.wrap({
      "Content-Type": "application/x-www-form-urlencoded",
    }),
    body: new URLSearchParams({
      resource: resourceName,
    }),
  }).then((rsp) => {
    rsp.text().then((responseText) => {
      d.innerHTML = responseText;
      evalInnerHtmlScripts(responseText, function () {
        Behaviour.applySubtree(d);
        const textarea = d.getElementsByTagName("TEXTAREA")[0];
        if (textarea) {
          textarea.focus();
        }
        // Prevent preview/hide-preview links from navigating to #
        d.addEventListener("click", function (e) {
          var link = e.target.closest("a");
          if (link && link.getAttribute("href") === "#") {
            e.preventDefault();
          }
        });
        // Intercept form submit to avoid page reload to wrong tab
        const form = d.querySelector("form");
        if (form) {
          form.addEventListener("submit", function (e) {
            e.preventDefault();
            const noteValue = textarea ? textarea.value : "";
            fetch("saveNote", {
              method: "post",
              headers: crumb.wrap({
                "Content-Type": "application/x-www-form-urlencoded",
              }),
              body: new URLSearchParams({
                resource: resourceName,
                note: noteValue,
              }),
            }).then(function () {
              // Preserve current tab and reload to get server-rendered content
              var activeTab = document.querySelector(".lr-tab-button--active");
              if (activeTab && !window.location.hash) {
                window.location.hash = activeTab.dataset.lrTab;
              }
              window.location.reload();
            });
          });
        }
        layoutUpdateCallback.call();
      });
    });
  });
}

function i18n(messageId, arg0, arg1, arg2) {
  const el = document.querySelector("#i18n");
  if (!el) return messageId;
  const val = el.getAttribute("data-" + messageId);
  if (!val) return messageId;
  var result = val;
  if (arg0 !== undefined) result = result.replace("{0}", arg0);
  if (arg1 !== undefined) result = result.replace("{1}", arg1);
  if (arg2 !== undefined) result = result.replace("{2}", arg2);
  return result;
}

document.addEventListener("DOMContentLoaded", function () {

  // Tab switching (with history state)
  function switchTab(tabId, pushState) {
    document.querySelectorAll(".lr-tab-button").forEach(function (b) {
      var active = b.dataset.lrTab === tabId;
      b.classList.toggle("lr-tab-button--active", active);
      b.classList.toggle("jenkins-button--tertiary", !active);
      b.setAttribute("aria-selected", String(active));
    });
    document.querySelectorAll(".lr-tab-pane").forEach(function (p) {
      p.classList.toggle("lr-tab-pane--active", p.id === "lr-tab-" + tabId);
    });
    if (pushState !== false) {
      var url = new URL(window.location);
      url.hash = tabId;
      history.pushState({ lrTab: tabId }, "", url);
    }
    if (typeof window.updateFilterMode === "function") window.updateFilterMode(tabId);
    if (tabId === "queue" && typeof window.loadQueuePage === "function") window.loadQueuePage();
  }

  document.querySelectorAll(".lr-tab-button").forEach(function (tabBtn) {
    tabBtn.addEventListener("click", function () {
      switchTab(tabBtn.dataset.lrTab);
    });
  });

  // Restore from URL hash or default to overview
  var hashTab = window.location.hash ? window.location.hash.substring(1) : null;
  if (hashTab && document.querySelector('.lr-tab-button[data-lr-tab="' + hashTab + '"]')) {
    switchTab(hashTab, false);
  }
  // else: stay on overview (the default active pane in HTML)

  // Browser back/forward
  window.addEventListener("popstate", function (e) {
    var tabId = (e.state && e.state.lrTab) ? e.state.lrTab : "overview";
    switchTab(tabId, false);
  });

  // Overview card headers navigate to tabs
  document.addEventListener("click", function (e) {
    var link = e.target.closest("[data-lr-tab-link]");
    if (!link) return;
    e.preventDefault();
    var tabId = link.getAttribute("data-lr-tab-link");
    switchTab(tabId);
  });

  // Helper: clear all resource tab filters
  function clearResourceFilters() {
    var quickInput = document.getElementById("lr-filter-quick");
    var statusSelect = document.getElementById("lr-filter-status");
    var heldByInput = document.getElementById("lr-filter-held-by");
    var labelInput = document.getElementById("lr-filter-label");
    var labelExprInput = document.getElementById("lr-filter-label-expression");
    if (quickInput) quickInput.value = "";
    if (statusSelect) statusSelect.value = "";
    if (heldByInput) heldByInput.value = "";
    if (labelInput) labelInput.value = "";
    if (labelExprInput) labelExprInput.value = "";
  }

  // Legend status pills navigate to resources tab with status filter
  document.addEventListener("click", function (e) {
    var pill = e.target.closest("[data-filter-status]");
    if (!pill) return;
    e.preventDefault();
    var status = pill.getAttribute("data-filter-status");
    switchTab("resources");
    setTimeout(function () {
      clearResourceFilters();
      var statusSelect = document.getElementById("lr-filter-status");
      if (statusSelect) {
        statusSelect.value = status;
        statusSelect.dispatchEvent(new Event("change", { bubbles: true }));
      }
    }, 100);
  });

  // Top label pills navigate to resources tab with label filter
  document.addEventListener("click", function (e) {
    var pill = e.target.closest("[data-filter-label]");
    if (!pill) return;
    e.preventDefault();
    var label = pill.getAttribute("data-filter-label");
    switchTab("resources");
    setTimeout(function () {
      clearResourceFilters();
      var labelInput = document.getElementById("lr-filter-label");
      if (labelInput) {
        labelInput.value = label;
        labelInput.dispatchEvent(new Event("input", { bubbles: true }));
      }
    }, 100);
  });

  // Most contended resource name navigates to resources tab with name filter
  document.addEventListener("click", function (e) {
    var link = e.target.closest("[data-filter-name]");
    if (!link) return;
    e.preventDefault();
    var name = link.getAttribute("data-filter-name");
    switchTab("resources");
    setTimeout(function () {
      clearResourceFilters();
      var quickInput = document.getElementById("lr-filter-quick");
      if (quickInput) {
        quickInput.value = name;
        quickInput.dispatchEvent(new Event("input", { bubbles: true }));
      }
    }, 100);
  });

  // Quick action buttons
  document.addEventListener("click", function (e) {
    var btn = e.target.closest(".lr-quick-action-btn");
    if (!btn) return;
    var action = btn.dataset.action;
    if (action === "reserveAll") {
      // Navigate to resources tab for selection
      switchTab("resources");
    } else if (action === "freeAll") {
      // Navigate to resources tab and select all locked/reserved for bulk action
      switchTab("resources");
      setTimeout(function () {
        document.querySelectorAll('#lr-tab-resources table tbody tr').forEach(function (row) {
          var status = (row.dataset.resourceStatus || "").toLowerCase();
          if (status === "reserved" || status === "locked") {
            var cb = row.querySelector('input[type="checkbox"]');
            if (cb && !cb.checked) { cb.click(); }
          }
        });
      }, 100);
    } else if (action === "resetQueued") {
      // Navigate to resources tab and select all queued resources
      switchTab("resources");
      setTimeout(function () {
        document.querySelectorAll('#lr-tab-resources table tbody tr').forEach(function (row) {
          var status = (row.dataset.resourceStatus || "").toLowerCase();
          if (status === "queued") {
            var cb = row.querySelector('input[type="checkbox"]');
            if (cb && !cb.checked) { cb.click(); }
          }
        });
      }, 100);
    } else if (action === "reserveByLabel") {
      // Switch to resources tab and focus the label filter
      switchTab("resources");
      setTimeout(function () {
        var labelInput = document.getElementById("lr-filter-label");
        if (labelInput) { labelInput.focus(); labelInput.select(); }
      }, 100);
    } else if (action === "myResources") {
      // Switch to resources tab and filter by current user using Held By column
      switchTab("resources");
      setTimeout(function () {
        var currentUser = (document.getElementById("lr-page-data") || {}).dataset;
        var user = currentUser ? currentUser.currentUser : "";
        if (!user) return;
        document.querySelectorAll('#lr-tab-resources table tbody tr').forEach(function (row) {
          var cells = row.querySelectorAll("td");
          var heldByText = cells.length > 4 ? (cells[4].textContent || "") : "";
          var owner = (heldByText || row.dataset.resourceOwner || "")
            .replace(/\s+/g, " ")
            .trim()
            .toLowerCase();
          var isMatch = owner.indexOf(user.toLowerCase()) !== -1;
          row.classList.toggle("lr-global-search-hidden", !isMatch);
        });
      }, 100);
    } else if (action === "recycleDeadLocks") {
      // Call server endpoint to free dead locks
      fetch("recycleDeadLocks", {
        method: "post",
        headers: crumb.wrap({}),
      }).then(function () {
        window.location.reload();
      });
    } else if (action === "stealAll") {
      // Navigate to resources tab and select all locked/reserved for steal
      switchTab("resources");
      setTimeout(function () {
        document.querySelectorAll('#lr-tab-resources table tbody tr').forEach(function (row) {
          var status = (row.dataset.resourceStatus || "").toLowerCase();
          if (status === "reserved" || status === "locked") {
            var cb = row.querySelector('input[type="checkbox"]');
            if (cb && !cb.checked) { cb.click(); }
          }
        });
      }, 100);
    }
  });

  // Threshold coloring for utilization percentages
  document.querySelectorAll(".lr-overview-stats-list .lr-overview-stat__number[data-pct]").forEach(function (el) {
    var pct = parseInt(el.dataset.pct, 10);
    var type = el.dataset.type;
    if (type === "free") {
      // Green when high, red when low
      if (pct <= 20) el.style.color = "var(--red)";
      else if (pct <= 40) el.style.color = "var(--orange)";
      else el.style.color = "var(--green)";
    } else if (type === "locked" || type === "reserved") {
      // Red when high
      if (pct >= 80) el.style.color = "var(--red)";
      else if (pct >= 50) el.style.color = "var(--orange)";
      else if (type === "locked") el.style.color = "var(--red)";
      else el.style.color = "var(--orange)";
    } else if (type === "inuse") {
      if (pct >= 80) el.style.color = "var(--red)";
      else if (pct >= 50) el.style.color = "var(--orange)";
      else el.style.color = "var(--text-color)";
    }
  });

  // Event delegation
  document.addEventListener("click", function (event) {
    let btn;
    if ((btn = event.target.closest(".lockable-resources-action-button"))) {
      resource_action(btn, btn.dataset.action);
    } else if ((btn = event.target.closest(".lockable-resources-delete-button"))) {
      deleteResource(btn);
    } else if ((btn = event.target.closest(".lockable-resources-change-queue-order"))) {
      changeQueueOrder(btn.dataset.queueItemId);
    } else if ((btn = event.target.closest(".lockable-resources-replace-note"))) {
      event.preventDefault();
      replaceNote(btn.dataset.resourceName);
    } else if (event.target.closest(".lr-add-resource-btn")) {
      showAddResourceDialog();
    } else if ((btn = event.target.closest(".lockable-resources-edit-button"))) {
      editResource(btn);
    }
  });

  // Client-side pagination for all tables
  initPagination();
});

// ====================================================================
// Client-side pagination
// ====================================================================

const PAGE_SIZES = [10, 25, 50, 100, 0];
const DEFAULT_PAGE_SIZE = 25;

function initPagination() {
  document.querySelectorAll("table.jenkins-table.sortable").forEach(function (table) {
    // Skip the queue table - it uses server-side pagination
    if (table.id === "lockable-resources-queue") return;
    const id = table.id || "table";
    const storageKey = "lr-page-size-" + id;
    let saved = null;
    try { saved = parseInt(localStorage.getItem(storageKey), 10); } catch (e) { /* noop */ }
    let pageSize = PAGE_SIZES.indexOf(saved) !== -1 ? saved : DEFAULT_PAGE_SIZE;
    let currentPage = 1;

    // Wrap table in a scroll container so pagination stays outside
    const scrollWrapper = document.createElement("div");
    scrollWrapper.className = "lr-table-scroll";
    table.parentNode.insertBefore(scrollWrapper, table);
    scrollWrapper.appendChild(table);

    // Place pagination in the toolbar (right of the eye icon) if available
    const controls = document.createElement("div");
    controls.className = "lr-pagination";
    const tabPane = table.closest(".lr-tab-pane");
    const toolbar = tabPane ? tabPane.querySelector(".lr-toolbar__actions") : null;
    if (toolbar) {
      toolbar.appendChild(controls);
    } else {
      scrollWrapper.parentNode.insertBefore(controls, scrollWrapper.nextSibling);
    }

    function getRows() {
      const tbody = table.querySelector("tbody");
      return tbody
        ? Array.from(tbody.children).filter(function (row) {
            return row.matches("tr")
              && !row.classList.contains("lr-filtered-out")
              && !row.classList.contains("lr-col-filtered-out");
          })
        : [];
    }

    function render() {
      const rows = getRows();
      const total = rows.length;
      const totalPages = pageSize === 0 ? 1 : Math.ceil(total / pageSize);
      if (currentPage > totalPages) currentPage = totalPages;
      if (currentPage < 1) currentPage = 1;

      // Show/hide rows
      rows.forEach(function (row, i) {
        if (pageSize === 0) {
          row.style.display = "";
        } else {
          const start = (currentPage - 1) * pageSize;
          row.style.display = (i >= start && i < start + pageSize) ? "" : "none";
        }
      });

      // Build controls HTML
      const opts = PAGE_SIZES.map(function (s) {
        return '<option value="' + s + '"' + (s === pageSize ? ' selected' : '') + '>' + (s === 0 ? 'All' : s) + '</option>';
      }).join('');
      let html = '<div class="lr-pagination__nav">'
        + '<div class="jenkins-select lr-pagination__select-wrapper"><select class="jenkins-select__input lr-pagination__select">' + opts + '</select></div>';
      if (totalPages > 1) {
        html += '<button class="jenkins-button jenkins-button--tertiary lr-pagination__prev"' + (currentPage <= 1 ? ' disabled' : '') + '>&lsaquo;</button>'
          + '<span class="lr-pagination__info">' + currentPage + ' / ' + totalPages + '</span>'
          + '<button class="jenkins-button jenkins-button--tertiary lr-pagination__next"' + (currentPage >= totalPages ? ' disabled' : '') + '>&rsaquo;</button>';
      }
      html += '</div>';

      controls.innerHTML = html;
    }

    // Event delegation on controls
    controls.addEventListener("change", function (e) {
      if (e.target.classList.contains("lr-pagination__select")) {
        pageSize = parseInt(e.target.value, 10);
        currentPage = 1;
        try { localStorage.setItem(storageKey, pageSize); } catch (ex) { /* noop */ }
        render();
      }
    });
    controls.addEventListener("click", function (e) {
      const totalPages = pageSize === 0 ? 1 : Math.ceil(getRows().length / pageSize);
      if (e.target.closest(".lr-pagination__prev") && currentPage > 1) { currentPage--; render(); }
      else if (e.target.closest(".lr-pagination__next") && currentPage < totalPages) { currentPage++; render(); }
    });

    // Re-paginate after sort or filter
    const observer = new MutationObserver(function () { currentPage = 1; render(); });
    const tbody = table.querySelector("tbody");
    if (tbody) {
      observer.observe(tbody, { childList: true });
    }
    table.addEventListener("lr-filter-changed", function () { currentPage = 1; render(); });

    render();
  });
}

// ====================================================================
// Tab change: hide/show add-resource button on overview
// ====================================================================
window.updateFilterMode = function (tabId) {
  const addBtn = document.querySelector(".lr-add-resource-btn");
  if (addBtn) addBtn.style.display = tabId === "overview" ? "none" : "";
};

// ============ Bulk selection & actions ============
(function () {
  const selectedResources = new Set();

  function updateBulkBar() {
    const bar = document.getElementById("lr-bulk-bar");
    if (!bar) return;
    const count = selectedResources.size;
    const countEl = document.getElementById("lr-bulk-count");
    if (countEl) countEl.textContent = count;
    bar.setAttribute("aria-hidden", count === 0 ? "true" : "false");
  }

  function syncSelectAll() {
    const selectAll = document.querySelector(".lr-select-all");
    if (!selectAll) return;
    const checkboxes = document.querySelectorAll(".lr-select-resource");
    const allChecked = checkboxes.length > 0 && Array.from(checkboxes).every(function (cb) { return cb.checked; });
    selectAll.checked = allChecked;
    selectAll.indeterminate = !allChecked && selectedResources.size > 0;
  }

  document.addEventListener("change", function (e) {
    if (e.target.classList.contains("lr-select-all")) {
      const checked = e.target.checked;
      document.querySelectorAll(".lr-select-resource").forEach(function (cb) {
        cb.checked = checked;
        const name = cb.getAttribute("data-resource-name");
        if (checked) {
          selectedResources.add(name);
        } else {
          selectedResources.delete(name);
        }
      });
      updateBulkBar();
      return;
    }

    if (e.target.classList.contains("lr-select-resource")) {
      const name = e.target.getAttribute("data-resource-name");
      if (e.target.checked) {
        selectedResources.add(name);
      } else {
        selectedResources.delete(name);
      }
      syncSelectAll();
      updateBulkBar();
    }
  });

  document.addEventListener("click", function (e) {
    const btn = e.target.closest(".lr-bulk-action");
    if (!btn) return;
    const action = btn.getAttribute("data-action");
    if (selectedResources.size === 0) return;
    bulkResourceAction(action, Array.from(selectedResources));
  });

  function bulkResourceAction(action, resources) {
    if (action === "reserve" || action === "steal" || action === "reassign") {
      dialog
        .prompt(i18n(action + "-title", resources.join(", ")), {
          message: i18n(action + "-message", resources.join(", ")),
          minWidth: "450px",
          maxWidth: "600px"
        })
        .then(function (reason) {
          executeBulk(action, resources, reason || "");
        });
      return;
    }
    executeBulk(action, resources, null);
  }

  function executeBulk(action, resources, reason) {
    let success = 0;
    let fail = 0;
    const total = resources.length;

    function next(idx) {
      if (idx >= total) {
        const msg = i18n("bulk-result", success, fail, total);
        if (fail > 0) {
          dialog.alert(msg);
        } else {
          notificationBar.show(msg, notificationBar.SUCCESS);
        }
        window.location.reload();
        return;
      }
      let url = action + "?resource=" + encodeURIComponent(resources[idx]);
      if (reason !== null) {
        url += "&reason=" + encodeURIComponent(reason);
      }
      fetch(url, {
        method: "post",
        headers: crumb.wrap({}),
      }).then(function (rsp) {
        if (rsp.ok) { success++; } else { fail++; }
        next(idx + 1);
      }).catch(function () {
        fail++;
        next(idx + 1);
      });
    }
    next(0);
  }

  // Clear selection when switching away from resources tab
  document.addEventListener("click", function (e) {
    const tab = e.target.closest("[data-lr-tab]");
    if (tab && tab.getAttribute("data-lr-tab") !== "resources") {
      selectedResources.clear();
      document.querySelectorAll(".lr-select-resource").forEach(function (cb) { cb.checked = false; });
      const selectAll = document.querySelector(".lr-select-all");
      if (selectAll) { selectAll.checked = false; selectAll.indeterminate = false; }
      updateBulkBar();
    }
  });
})();

// ============ Per-column inline filters (factory) ============
function initColumnFilters(config) {
  // config: { storageKey, tableId, filterClass, applyFn }
  // applyFn(table): reads filter inputs and applies row visibility

  function persist() {
    try {
      var state = {};
      document.querySelectorAll("." + config.filterClass).forEach(function (f) {
        if (f.id && f.value) state[f.id] = f.value;
      });
      localStorage.setItem(config.storageKey, JSON.stringify(state));
    } catch (e) { /* noop */ }
  }

  function apply() {
    var table = document.getElementById(config.tableId);
    if (!table) return;
    config.applyFn(table);
    persist();
  }

  function restore() {
    try {
      var raw = localStorage.getItem(config.storageKey);
      if (!raw) return;
      var state = JSON.parse(raw);
      Object.keys(state).forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.value = state[id];
      });
      apply();
    } catch (e) { /* noop */ }
  }

  document.addEventListener("input", function (e) {
    if (e.target.classList.contains(config.filterClass)) apply();
  });
  document.addEventListener("change", function (e) {
    if (e.target.classList.contains(config.filterClass)) apply();
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", restore);
  } else {
    restore();
  }
}

// ============ Column visibility toggle (factory) ============
function initColumnVisibility(config) {
  // config: { storageKey, tableId, menuId, toggleId, dataAttr, columns }

  function getVisibility() {
    try {
      var raw = localStorage.getItem(config.storageKey);
      return raw ? JSON.parse(raw) : {};
    } catch (e) { return {}; }
  }

  function setVisibility(key, visible) {
    var state = getVisibility();
    state[key] = visible;
    try { localStorage.setItem(config.storageKey, JSON.stringify(state)); } catch (e) { /* noop */ }
  }

  function applyVisibility() {
    var table = document.getElementById(config.tableId);
    if (!table) return;
    var state = getVisibility();
    config.columns.forEach(function (col) {
      var visible = state[col.key] !== false;
      table.querySelectorAll("thead tr").forEach(function (tr) {
        var cell = tr.querySelectorAll("th")[col.idx];
        if (cell) cell.classList.toggle("lr-col-hidden", !visible);
      });
      table.querySelectorAll("tbody tr").forEach(function (tr) {
        var cell = tr.querySelectorAll("td")[col.idx];
        if (cell) cell.classList.toggle("lr-col-hidden", !visible);
      });
    });
  }

  function buildMenu() {
    var container = document.getElementById(config.menuId);
    if (!container) return;
    var state = getVisibility();
    container.innerHTML = "";

    // Select All / Deselect All controls
    var controls = document.createElement("div");
    controls.className = "lr-col-vis-controls";
    var selectAll = document.createElement("button");
    selectAll.type = "button";
    selectAll.className = "jenkins-button jenkins-button--tertiary";
    selectAll.textContent = "All";
    selectAll.addEventListener("click", function () {
      toggleAll(true);
    });
    var deselectAll = document.createElement("button");
    deselectAll.type = "button";
    deselectAll.className = "jenkins-button jenkins-button--tertiary";
    deselectAll.textContent = "None";
    deselectAll.addEventListener("click", function () {
      toggleAll(false);
    });

    // Highlight active state
    var allChecked = config.columns.every(function (col) { return state[col.key] !== false; });
    var noneChecked = config.columns.every(function (col) { return state[col.key] === false; });
    if (allChecked) selectAll.classList.add("lr-col-vis-controls--active");
    if (noneChecked) deselectAll.classList.add("lr-col-vis-controls--active");

    controls.appendChild(selectAll);
    controls.appendChild(deselectAll);
    container.appendChild(controls);

    config.columns.forEach(function (col) {
      var checked = state[col.key] !== false;
      var wrapper = document.createElement("div");
      wrapper.className = "jenkins-checkbox lr-col-vis-item";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = checked;
      cb.id = config.menuId + "-" + col.key;
      cb.setAttribute("data-" + config.dataAttr, col.key);
      var label = document.createElement("label");
      label.setAttribute("for", cb.id);
      label.textContent = col.label;
      wrapper.appendChild(cb);
      wrapper.appendChild(label);
      container.appendChild(wrapper);
    });
  }

  function toggleAll(checked) {
    config.columns.forEach(function (col) {
      setVisibility(col.key, checked);
    });
    applyVisibility();
    buildMenu();
  }

  document.addEventListener("change", function (e) {
    if (e.target.getAttribute("data-" + config.dataAttr)) {
      setVisibility(e.target.getAttribute("data-" + config.dataAttr), e.target.checked);
      applyVisibility();
    }
  });

  document.addEventListener("click", function (e) {
    var toggle = e.target.closest("#" + config.toggleId);
    var menu = document.getElementById(config.menuId);
    if (!menu) return;
    if (toggle) {
      var open = menu.style.display === "block";
      menu.style.display = open ? "none" : "block";
      return;
    }
    if (!menu.contains(e.target)) {
      menu.style.display = "none";
    }
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () { buildMenu(); applyVisibility(); });
  } else {
    buildMenu();
    applyVisibility();
  }
}

// ============ Instantiate filters & column visibility for all tabs ============
(function () {
  var resourcesExprState = {
    pendingExpr: null,
    loadedExpr: null,
    inflight: false,
    timer: null,
    allowedNames: null,
  };

  function isProbablyCompleteLabelExpression(expr) {
    // Heuristic to prevent calling the server on intermediate/incomplete inputs while typing.
    // We still rely on the server for actual validation.
    if (!expr) return true;

    // Must contain at least one label-ish character.
    try {
      if (!/[\p{L}\p{N}_\-\.]/u.test(expr)) return false;
    } catch (e) {
      // Fallback for older JS engines without unicode property escapes.
      if (!/[A-Za-z0-9_\-\.]/.test(expr)) return false;
    }

    // Parentheses must be balanced.
    var depth = 0;
    for (var i = 0; i < expr.length; i++) {
      var ch = expr.charAt(i);
      if (ch === "(") depth++;
      else if (ch === ")") {
        depth--;
        if (depth < 0) return false;
      }
    }
    if (depth !== 0) return false;

    // Trailing operators are incomplete.
    if (/(&&|\|\||[&|])\s*$/.test(expr)) return false;
    if (/!\s*$/.test(expr)) return false;
    if (/\(\s*$/.test(expr)) return false;

    return true;
  }

  function scheduleResourcesExpressionReload(table, expr, afterReload) {
    if (!table) return;
    var trimmed = (expr || "").trim();

    // Empty expression = no server-side restriction.
    if (!trimmed) {
      resourcesExprState.pendingExpr = "";
      resourcesExprState.loadedExpr = "";
      resourcesExprState.allowedNames = null;
      return;
    }

    // Don't query the server for intermediate input while the user is typing.
    if (!isProbablyCompleteLabelExpression(trimmed)) {
      resourcesExprState.pendingExpr = trimmed;
      resourcesExprState.loadedExpr = "";
      resourcesExprState.allowedNames = null;
      return;
    }

    resourcesExprState.pendingExpr = trimmed;

    if (resourcesExprState.timer) {
      clearTimeout(resourcesExprState.timer);
    }

    resourcesExprState.timer = setTimeout(function () {
      if (resourcesExprState.inflight) {
        return;
      }

      if (resourcesExprState.loadedExpr === resourcesExprState.pendingExpr) {
        return;
      }

      resourcesExprState.inflight = true;
      var endpoint = table.dataset.resourcesByLabelExpressionUrl || "getResourcesByLabelExpression";
      var url = endpoint + "?expr=" + encodeURIComponent(resourcesExprState.pendingExpr);

      fetch(url, { headers: { "Accept": "application/json" } })
        .then(function (rsp) {
          if (rsp.ok) return rsp.json();
          return rsp.text().then(function (t) {
            throw new Error(t || ("HTTP " + rsp.status));
          });
        })
        .then(function (data) {
          var set = new Set();
          (data.items || []).forEach(function (it) {
            if (it && it.name) set.add(it.name);
          });
          resourcesExprState.allowedNames = set;
          resourcesExprState.loadedExpr = resourcesExprState.pendingExpr;
          if (typeof afterReload === "function") afterReload();
        })
        .catch(function (err) {
          if (typeof notificationBar !== "undefined" && notificationBar && notificationBar.show) {
            notificationBar.show(
              "Failed to apply label expression filter: " + (err && err.message ? err.message : err),
              notificationBar.ERROR);
          }
        })
        .finally(function () {
          resourcesExprState.inflight = false;
          if (resourcesExprState.loadedExpr !== resourcesExprState.pendingExpr) {
            scheduleResourcesExpressionReload(table, resourcesExprState.pendingExpr, afterReload);
          }
        });
    }, 250);
  }

  function applyResourcesFilters(table) {
    var quickInput = document.getElementById("lr-filter-quick");
    var statusSelect = document.getElementById("lr-filter-status");
    var heldByInput = document.getElementById("lr-filter-held-by");
    var labelInput = document.getElementById("lr-filter-label");
    var labelExprInput = document.getElementById("lr-filter-label-expression");

    var nameVal = quickInput ? quickInput.value.trim().toLowerCase() : "";
    var statusVal = statusSelect ? statusSelect.value : "";
    var heldByVal = heldByInput ? heldByInput.value.trim().toLowerCase() : "";
    var labelVal = labelInput ? labelInput.value.trim().toLowerCase() : "";
    var labelExprVal = labelExprInput ? labelExprInput.value.trim() : "";

    scheduleResourcesExpressionReload(table, labelExprVal, function () {
      applyResourcesFilters(table);
    });

    var allowedSet = null;
    if (labelExprVal && resourcesExprState.loadedExpr === labelExprVal) {
      allowedSet = resourcesExprState.allowedNames;
    }

    var tbody = table.querySelector("tbody");
    if (!tbody) return;

    Array.from(tbody.querySelectorAll(":scope > tr")).forEach(function (row) {
      var resourceName = row.dataset.resourceName || "";
      var exprOk = !labelExprVal || !allowedSet || allowedSet.has(resourceName);
      var show = exprOk
        && (!nameVal || resourceName.toLowerCase().indexOf(nameVal) !== -1)
        && (!statusVal || (row.dataset.resourceStatus || "") === statusVal)
        && (!heldByVal || (row.dataset.resourceOwner || "").toLowerCase().indexOf(heldByVal) !== -1)
        && (!labelVal || (row.dataset.resourceLabels || "").toLowerCase().indexOf(labelVal) !== -1);
      row.classList.toggle("lr-col-filtered-out", !show);
    });
    table.dispatchEvent(new CustomEvent("lr-filter-changed"));
  }

  // Resources tab filters
  initColumnFilters({
    storageKey: "lockable-resources-col-filters",
    tableId: "lockable-resources",
    filterClass: "lr-col-filter",
    applyFn: function (table) {
      applyResourcesFilters(table);
    }
  });

  // Labels tab filters
  initColumnFilters({
    storageKey: "lockable-resources-labels-filters",
    tableId: "lockable-resources-labels",
    filterClass: "lr-labels-filter",
    applyFn: function (table) {
      var nameInput = document.getElementById("lr-labels-filter-name");
      var assignedInput = document.getElementById("lr-labels-filter-assigned");
      var nameVal = nameInput ? nameInput.value.trim().toLowerCase() : "";
      var assignedVal = assignedInput ? assignedInput.value.trim() : "";
      var tbody = table.querySelector("tbody");
      if (!tbody) return;

      Array.from(tbody.querySelectorAll(":scope > tr")).forEach(function (row) {
        var show = (!nameVal || (row.dataset.labelName || "").toLowerCase().indexOf(nameVal) !== -1)
          && (!assignedVal || (row.dataset.labelAssigned || "") === assignedVal);
        row.classList.toggle("lr-col-filtered-out", !show);
      });
    }
  });

  // Queue tab filters
  initColumnFilters({
    storageKey: "lockable-resources-queue-filters",
    tableId: "lockable-resources-queue",
    filterClass: "lr-queue-filter",
    applyFn: function () {
      if (typeof window.reloadQueuePage === "function") {
        window.reloadQueuePage();
      }
    }
  });

  // Resources column visibility
  initColumnVisibility({
    storageKey: "lockable-resources-col-visibility",
    tableId: "lockable-resources",
    menuId: "lr-col-visibility-menu",
    toggleId: "lr-col-visibility-toggle",
    dataAttr: "col-key",
    columns: [
      { idx: 1, key: "index", label: "#" },
      { idx: 2, key: "name", label: "Resource" },
      { idx: 3, key: "status", label: "Status" },
      { idx: 4, key: "heldBy", label: "Held By" },
      { idx: 5, key: "reason", label: "Reason" },
      { idx: 6, key: "since", label: "Since" },
      { idx: 7, key: "labels", label: "Labels" },
      { idx: 8, key: "properties", label: "Properties" },
      { idx: 9, key: "actions", label: "Actions" }
    ]
  });

  // Labels column visibility
  initColumnVisibility({
    storageKey: "lockable-resources-labels-col-visibility",
    tableId: "lockable-resources-labels",
    menuId: "lr-labels-col-visibility-menu",
    toggleId: "lr-labels-col-visibility-toggle",
    dataAttr: "labels-col-key",
    columns: [
      { idx: 0, key: "labels", label: "Labels" },
      { idx: 1, key: "assigned", label: "Assigned Resources" },
      { idx: 2, key: "free", label: "Available" },
      { idx: 3, key: "percentage", label: "Availability %" }
    ]
  });

  // Queue column visibility
  initColumnVisibility({
    storageKey: "lockable-resources-queue-col-visibility",
    tableId: "lockable-resources-queue",
    menuId: "lr-queue-col-visibility-menu",
    toggleId: "lr-queue-col-visibility-toggle",
    dataAttr: "queue-col-key",
    columns: [
      { idx: 0, key: "position", label: "Position" },
      { idx: 1, key: "action", label: "Reorder" },
      { idx: 2, key: "type", label: "Type" },
      { idx: 3, key: "request", label: "Resource(s)" },
      { idx: 4, key: "reason", label: "Reason" },
      { idx: 5, key: "requestedBy", label: "Requested By" },
      { idx: 6, key: "requestedAt", label: "Queued At" },
      { idx: 7, key: "priority", label: "Priority" },
      { idx: 8, key: "queueId", label: "Queue ID" }
    ]
  });
})();

// ============ Global cross-field search ============
(function () {
  var STORAGE_KEY = "lockable-resources-global-search";
  var TAB_TABLE_MAP = {
    resources: "#lr-tab-resources table",
    labels: "#lr-tab-labels table",
    queue: "#lr-tab-queue table"
  };

  function getState() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {};
    } catch (e) { return {}; }
  }

  function saveState(state) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch (e) { /* noop */ }
  }

  function applySearch(wrapper) {
    var tab = wrapper.dataset.tab;
    var input = wrapper.querySelector(".lr-global-search-input");
    var query = (input.value || "").toLowerCase().trim();
    var table = document.querySelector(TAB_TABLE_MAP[tab]);
    if (!table) return;
    var tbody = table.querySelector("tbody");
    if (!tbody) return;
    Array.from(tbody.querySelectorAll(":scope > tr")).forEach(function (row) {
      if (!query) {
        row.classList.remove("lr-global-search-hidden");
        return;
      }
      var text = row.textContent.toLowerCase();
      row.classList.toggle("lr-global-search-hidden", text.indexOf(query) === -1);
    });
    var state = getState();
    if (query) { state[tab] = query; } else { delete state[tab]; }
    saveState(state);
  }

  function restoreAll() {
    var state = getState();
    document.querySelectorAll(".lr-global-search-wrapper").forEach(function (wrapper) {
      var tab = wrapper.dataset.tab;
      var input = wrapper.querySelector(".lr-global-search-input");
      if (state[tab]) {
        input.value = state[tab];
        var popup = wrapper.querySelector(".lr-global-search-popup");
        popup.style.display = "";
        wrapper.querySelector(".lr-global-search-toggle").classList.add("lr-global-search-toggle--active");
        applySearch(wrapper);
      }
    });
  }

  // Toggle popup
  document.addEventListener("click", function (e) {
    var toggleBtn = e.target.closest(".lr-global-search-toggle");
    if (toggleBtn) {
      var wrapper = toggleBtn.closest(".lr-global-search-wrapper");
      var popup = wrapper.querySelector(".lr-global-search-popup");
      var isOpen = popup.style.display !== "none";
      if (isOpen) {
        popup.style.display = "none";
        toggleBtn.classList.remove("lr-global-search-toggle--active");
      } else {
        popup.style.display = "";
        toggleBtn.classList.add("lr-global-search-toggle--active");
        popup.querySelector(".lr-global-search-input").focus();
      }
      return;
    }
    // Close if click outside
    document.querySelectorAll(".lr-global-search-wrapper").forEach(function (wrapper) {
      if (!wrapper.contains(e.target)) {
        var popup = wrapper.querySelector(".lr-global-search-popup");
        var input = wrapper.querySelector(".lr-global-search-input");
        if (!input.value) {
          popup.style.display = "none";
          wrapper.querySelector(".lr-global-search-toggle").classList.remove("lr-global-search-toggle--active");
        }
      }
    });
  });

  // Filter on input
  document.addEventListener("input", function (e) {
    if (!e.target.classList.contains("lr-global-search-input")) return;
    var wrapper = e.target.closest(".lr-global-search-wrapper");
    applySearch(wrapper);
  });

  // Restore on load
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", restoreAll);
  } else {
    restoreAll();
  }
})();

// ============ Reset filters ============
(function () {
  document.addEventListener("click", function (e) {
    var btn = e.target.closest(".lr-reset-filters-btn");
    if (!btn) return;
    var pane = btn.closest(".lr-tab-pane");
    if (!pane) return;

    // Clear column/field filter inputs and selects
    pane.querySelectorAll(".lr-col-filter, .lr-labels-filter, .lr-queue-filter").forEach(function (f) {
      if (f.tagName === "SELECT") {
        f.selectedIndex = 0;
      } else {
        f.value = "";
      }
    });

    // Clear global search for this tab
    var searchWrapper = pane.querySelector(".lr-global-search-wrapper");
    if (searchWrapper) {
      var input = searchWrapper.querySelector(".lr-global-search-input");
      if (input) input.value = "";
      var popup = searchWrapper.querySelector(".lr-global-search-popup");
      if (popup) popup.style.display = "none";
      var toggle = searchWrapper.querySelector(".lr-global-search-toggle");
      if (toggle) toggle.classList.remove("lr-global-search-toggle--active");
    }

    // Re-apply filter logic to show all rows
    pane.querySelectorAll("table tbody tr").forEach(function (row) {
      row.classList.remove("lr-filtered-out", "lr-col-filtered-out", "lr-global-search-hidden");
    });

    // Clear persisted filter state
    try {
      var tabId = pane.id;
      if (tabId === "lr-tab-resources") {
        localStorage.removeItem("lockable-resources-col-filters");
      } else if (tabId === "lr-tab-labels") {
        localStorage.removeItem("lockable-resources-labels-filters");
      } else if (tabId === "lr-tab-queue") {
        localStorage.removeItem("lockable-resources-queue-filters");
      }
      // Clear global search state for this tab
      var gsKey = "lockable-resources-global-search";
      var gs = JSON.parse(localStorage.getItem(gsKey) || "{}");
      var tab = searchWrapper ? searchWrapper.dataset.tab : "";
      if (tab && gs[tab]) {
        delete gs[tab];
        localStorage.setItem(gsKey, JSON.stringify(gs));
      }
    } catch (ex) { /* noop */ }
  });
})();

// ====================================================================
// Server-side queue pagination
// ====================================================================
(function () {
  var queueTable = document.getElementById("lockable-resources-queue");
  if (!queueTable) return;

  var endpointUrl = queueTable.dataset.queueUrl;
  var rootUrl = queueTable.dataset.rootUrl || "";
  var hasQueuePermission = !!document.getElementById("lr-queue-has-permission");
  var queueOrderIconTemplate = document.getElementById("lr-queue-order-icon-template");
  var queueOrderIconHtml = queueOrderIconTemplate ? queueOrderIconTemplate.innerHTML : "";
  var tbody = document.getElementById("lr-queue-tbody");
  var statusEl = document.getElementById("lr-queue-status");
  var queueToolbarActions = document.querySelector("#lr-tab-queue .lr-toolbar__actions");
  var paginationEl = document.getElementById("lr-queue-pagination");

  if (!paginationEl) {
    paginationEl = document.createElement("div");
    paginationEl.id = "lr-queue-pagination";
    paginationEl.className = "lr-queue-pagination";
  }

  // Keep queue pagination in the toolbar, aligned with other tabs
  if (queueToolbarActions && paginationEl.parentElement !== queueToolbarActions) {
    queueToolbarActions.appendChild(paginationEl);
  }

  var currentPage = 1;
  var pageSize = 25;
  var queueLoaded = false;

  try {
    var saved = parseInt(localStorage.getItem("lr-queue-page-size"), 10);
    if ([10, 25, 50, 100].indexOf(saved) !== -1) pageSize = saved;
  } catch (e) { /* noop */ }

  function escapeHtml(str) {
    var div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
  }

  function renderRow(item) {
    var requestText = item.requestText || "";
    var requestedByText = item.requestedBy || "";
    var html = "<tr data-queue-type=\"" + escapeHtml(item.type) + "\" data-queue-request=\"" + escapeHtml(requestText) + "\" data-queue-requested-by=\"" + escapeHtml(requestedByText) + "\" data-queue-id=\"" + escapeHtml(item.id) + "\" data-queue-priority=\"" + item.priority + "\">";

    // Position
    html += "<td>" + item.index + "</td>";

    // Action
    html += "<td>";
    if (hasQueuePermission) {
      html += "<button data-queue-item-id=\"" + escapeHtml(item.id) + "\" class=\"jenkins-button jenkins-button--tertiary jenkins-!-success-color lockable-resources-change-queue-order\" title=\"Change Position\" tooltip=\"Change Position\" aria-label=\"Change Position\">" + queueOrderIconHtml + "</button>";
    }
    html += "</td>";

    // Request type, info, reason
    if (item.type === "resources") {
      html += "<td>Resources</td>";
      var resources = item.request || [];
      if (resources.length > 0) {
        var resHtml = resources.map(function (r) {
          var s = "<div class=\"lr-resource-header\"><strong>" + escapeHtml(r.name) + "</strong>";
          if (r.ephemeral) s += " <span class=\"lr-static-label\">Ephemeral</span>";
          s += "</div>";
          if (r.description) s += "<div class=\"lr-resource-description\">" + escapeHtml(r.description) + "</div>";
          return s;
        }).join("");
        html += "<td>" + resHtml + "</td>";
      } else {
        html += "<td></td>";
      }
      html += "<td></td>";
    } else if (item.type === "label") {
      html += "<td>Label</td>";
      html += "<td><a class=\"jenkins-table__link model-link\" href=\"" + rootUrl + "/label/" + encodeURIComponent(item.request) + "\">" + escapeHtml(item.request) + "</a></td>";
      html += "<td>" + escapeHtml(item.reason) + "</td>";
    } else {
      html += "<td>Groovy expression</td>";
      html += "<td>" + escapeHtml(item.request) + "</td>";
      html += "<td></td>";
    }

    // Requested by
    html += "<td>";
    if (item.requestedBy && item.requestedByUrl) {
      html += "<a class=\"jenkins-table__link jenkins-table__badge model-link inside\" href=\"" + rootUrl + "/" + escapeHtml(item.requestedByUrl) + "\">" + escapeHtml(item.requestedBy) + "</a>";
    } else if (item.requestedBy) {
      html += escapeHtml(item.requestedBy);
    } else {
      html += "N/A";
    }
    html += "</td>";

    // Requested at
    html += "<td>";
    if (item.queuedAtHuman) html += escapeHtml(item.queuedAtHuman) + " ago";
    html += "</td>";

    // Priority
    html += "<td>" + item.priority + "</td>";

    // ID
    html += "<td>" + escapeHtml(item.id) + "</td>";

    html += "</tr>";
    return html;
  }

  function renderPagination(data) {
    if (data.total === 0) { paginationEl.innerHTML = ""; return; }
    var opts = [10, 25, 50, 100].map(function (s) {
      return "<option value=\"" + s + "\"" + (s === pageSize ? " selected" : "") + ">" + s + "</option>";
    }).join("");
    var html = "<div class=\"lr-pagination\"><div class=\"lr-pagination__nav\">"
      + "<div class=\"jenkins-select lr-pagination__select-wrapper\"><select class=\"jenkins-select__input lr-pagination__select lr-queue-page-size\">" + opts + "</select></div>";
    if (data.pages > 1) {
      html += "<button class=\"jenkins-button jenkins-button--tertiary lr-queue-prev\"" + (currentPage <= 1 ? " disabled" : "") + ">&lsaquo;</button>"
        + "<span class=\"lr-pagination__info\">" + currentPage + " / " + data.pages + "</span>"
        + "<button class=\"jenkins-button jenkins-button--tertiary lr-queue-next\"" + (currentPage >= data.pages ? " disabled" : "") + ">&rsaquo;</button>";
    }
    html += "</div></div>";
    paginationEl.innerHTML = html;
  }

  function loadPage(page) {
    currentPage = page || 1;
    var params = new URLSearchParams();
    params.set("page", currentPage);
    params.set("size", pageSize);

    var typeInput = document.getElementById("lr-queue-filter-type");
    var requestInput = document.getElementById("lr-queue-filter-request");
    var requestedByInput = document.getElementById("lr-queue-filter-requested-by");
    if (typeInput && typeInput.value.trim()) params.set("type", typeInput.value.trim());
    if (requestInput && requestInput.value.trim()) params.set("request", requestInput.value.trim());
    if (requestedByInput && requestedByInput.value.trim()) params.set("requestedBy", requestedByInput.value.trim());

    var url = endpointUrl + "?" + params.toString();
    tbody.innerHTML = "<tr><td colspan=\"9\" class=\"lr-queue-loading\">Loading...</td></tr>";

    fetch(url, { headers: crumb.wrap({}) })
      .then(function (rsp) {
        if (!rsp.ok) throw new Error("HTTP " + rsp.status);
        return rsp.json();
      })
      .then(function (data) {
        // Status/warning
        if (data.total === 0) {
          statusEl.innerHTML = "<p>The queue is currently empty.</p>";
          tbody.innerHTML = "";
        } else {
          if (data.warningCount) {
            statusEl.innerHTML = "<p class=\"jenkins-!-warning-color\">The queue has " + data.warningCount + " item(s). The oldest one was inserted " + escapeHtml(data.warningAge) + " ago!</p>";
          } else {
            statusEl.innerHTML = "";
          }
          tbody.innerHTML = data.items.map(renderRow).join("");
        }
        renderPagination(data);
      })
      .catch(function (err) {
        tbody.innerHTML = "<tr><td colspan=\"9\">Error loading queue: " + escapeHtml(err.message) + "</td></tr>";
        statusEl.innerHTML = "";
        paginationEl.innerHTML = "";
      });
  }

  // Event delegation for pagination controls
  paginationEl.addEventListener("change", function (e) {
    if (e.target.classList.contains("lr-queue-page-size")) {
      pageSize = parseInt(e.target.value, 10);
      try { localStorage.setItem("lr-queue-page-size", pageSize); } catch (ex) { /* noop */ }
      loadPage(1);
    }
  });
  paginationEl.addEventListener("click", function (e) {
    if (e.target.closest(".lr-queue-prev") && currentPage > 1) loadPage(currentPage - 1);
    else if (e.target.closest(".lr-queue-next")) loadPage(currentPage + 1);
  });

  // Expose for tab switching
  window.loadQueuePage = function () {
    if (!queueLoaded) {
      queueLoaded = true;
      loadPage(1);
    }
  };

  window.reloadQueuePage = function () {
    if (queueLoaded) {
      loadPage(1);
    }
  };

  // Auto-load if queue tab is already active (e.g. direct link)
  var queuePane = document.getElementById("lr-tab-queue");
  if (queuePane && queuePane.classList.contains("lr-tab-pane--active")) {
    loadPage(1);
    queueLoaded = true;
  }
})();
