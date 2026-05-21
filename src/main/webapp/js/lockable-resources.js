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
      Behaviour.applySubtree(d);
      const textarea = d.getElementsByTagName("TEXTAREA")[0];
      if (textarea) {
        textarea.focus();
      }
      layoutUpdateCallback.call();
    });
  });
}

function i18n(messageId, arg0, arg1) {
  const el = document.querySelector("#i18n");
  if (!el) return messageId;
  const val = el.getAttribute("data-" + messageId);
  if (!val) return messageId;
  return val.replace("{0}", arg0).replace("{1}", arg1);
}

document.addEventListener("DOMContentLoaded", function () {

  // Tab switching
  document.querySelectorAll(".lr-tab-button").forEach(function (tabBtn) {
    tabBtn.addEventListener("click", function () {
      const tabId = tabBtn.dataset.lrTab;
      document.querySelectorAll(".lr-tab-button").forEach(function (b) {
        const active = b === tabBtn;
        b.classList.toggle("lr-tab-button--active", active);
        b.classList.toggle("jenkins-button--tertiary", !active);
        b.setAttribute("aria-selected", String(active));
      });
      document.querySelectorAll(".lr-tab-pane").forEach(function (p) {
        p.classList.toggle("lr-tab-pane--active", p.id === "lr-tab-" + tabId);
      });
      try { localStorage.setItem("lockable-resources-active-tab", tabId); } catch (e) { /* noop */ }
      if (typeof window.updateFilterMode === "function") window.updateFilterMode(tabId);
    });
  });

  // Restore last active tab
  try {
    const activeTab = localStorage.getItem("lockable-resources-active-tab");
    if (activeTab) {
      const restoreBtn = document.querySelector('.lr-tab-button[data-lr-tab="' + activeTab + '"]');
      if (restoreBtn) restoreBtn.click();
    }
  } catch (e) { /* noop */ }

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

  // ====================================================================
  // Filter / search (adapts per active tab)
  // ====================================================================
  initFilter();
});

// ====================================================================
// Client-side pagination
// ====================================================================

const PAGE_SIZES = [10, 25, 50, 100, 0];
const DEFAULT_PAGE_SIZE = 25;

function initPagination() {
  document.querySelectorAll("table.jenkins-table.sortable").forEach(function (table) {
    const id = table.id || "table";
    const storageKey = "lr-page-size-" + id;
    let saved = null;
    try { saved = parseInt(localStorage.getItem(storageKey), 10); } catch (e) { /* noop */ }
    let pageSize = PAGE_SIZES.indexOf(saved) !== -1 ? saved : DEFAULT_PAGE_SIZE;
    let currentPage = 1;

    const controls = document.createElement("div");
    controls.className = "lr-pagination";
    table.parentNode.insertBefore(controls, table.nextSibling);

    function getRows() {
      const tbody = table.querySelector("tbody");
      return tbody ? Array.from(tbody.querySelectorAll(":scope > tr:not(.lr-filtered-out)")) : [];
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
// Filter / search — adapts per active tab
// ====================================================================

const TABLE_IDS = { resources: "lockable-resources", labels: "lockable-resources-labels", queue: "lockable-resources-queue" };

function filterRows(tableId, predicate) {
  const table = document.getElementById(tableId);
  if (!table) return;
  const tbody = table.querySelector("tbody");
  if (!tbody) return;
  Array.from(tbody.querySelectorAll(":scope > tr")).forEach(function (row) {
    row.classList.toggle("lr-filtered-out", !predicate(row));
    row.style.display = "";
  });
  table.dispatchEvent(new CustomEvent("lr-filter-changed"));
}

function initFilter() {
  const quickInput = document.getElementById("lr-filter-quick");
  const nameInput = document.getElementById("lr-filter-name");
  const labelInput = document.getElementById("lr-filter-label");
  const statusSelect = document.getElementById("lr-filter-status");
  const textInput = document.getElementById("lr-filter-text");
  const resourcesPane = document.querySelector(".lr-filter-resources");
  const searchPane = document.querySelector(".lr-filter-search");
  const searchPopover = document.querySelector(".lr-search-popover");
  const filterPopover = document.querySelector(".lr-filter-popover");
  const searchToggle = document.querySelector(".lr-search-toggle");
  const filterToggle = document.querySelector(".lr-filter-toggle");
  if (!quickInput) return; // no filter on page (0 resources)

  const STORAGE_KEY = "lockable-resources-filter";
  let activeTab = "resources";
  let searchOpen = false;
  let advancedOpen = false;

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : {};
    } catch (e) { return {}; }
  }

  function saveState(patch) {
    try {
      const state = Object.assign(loadState(), patch);
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) { /* noop */ }
  }

  // Quick search across all columns of the active table
  function applyQuickFilter() {
    const query = quickInput.value.trim().toLowerCase();
    const tableId = TABLE_IDS[activeTab];
    if (!tableId) return;
    filterRows(tableId, function (row) {
      return !query || row.textContent.toLowerCase().indexOf(query) !== -1;
    });
    saveState({ quick: quickInput.value, tab: activeTab });
  }

  // Structured filter for resources tab only
  function applyStructuredFilter() {
    const nameVal = nameInput.value.trim().toLowerCase();
    const labelVal = labelInput.value.trim().toLowerCase();
    const statusVal = statusSelect.value;
    filterRows(TABLE_IDS.resources, function (row) {
      return (!nameVal || (row.dataset.resourceName || "").toLowerCase().indexOf(nameVal) !== -1)
        && (!labelVal || (row.dataset.resourceLabels || "").toLowerCase().indexOf(labelVal) !== -1)
        && (!statusVal || (row.dataset.resourceStatus || "") === statusVal);
    });
    saveState({ name: nameInput.value, label: labelInput.value, status: statusVal });
  }

  function applyActiveFilter() {
    if (advancedOpen && activeTab === "resources") {
      applyStructuredFilter();
    } else if (searchOpen) {
      applyQuickFilter();
    }
  }

  // Toggle search popover
  function setSearchOpen(open, skipFocus) {
    searchOpen = open;
    if (!searchPopover) return;
    searchPopover.style.display = open ? "block" : "none";
    searchToggle?.classList.toggle("lr-filter-active", open);
    if (open) {
      if (advancedOpen) setAdvancedOpen(false, true);
      if (!skipFocus) quickInput.focus();
    }
    saveState({ mode: open ? "search" : "closed" });
    applyActiveFilter();
  }

  // Toggle advanced filter popover
  function setAdvancedOpen(open, skipFocus) {
    advancedOpen = open;
    if (!filterPopover) return;
    filterPopover.style.display = open ? "flex" : "none";
    filterToggle?.classList.toggle("lr-filter-active", open);
    if (open) {
      if (searchOpen) setSearchOpen(false, true);
      if (!skipFocus) {
        if (activeTab === "resources") nameInput?.focus();
        else textInput?.focus();
      }
    }
    saveState({ mode: open ? "advanced" : "closed" });
    applyActiveFilter();
  }

  // Tab change: update filter panes
  window.updateFilterMode = function (tabId) {
    activeTab = tabId;
    if (resourcesPane) resourcesPane.style.display = tabId === "resources" ? "flex" : "none";
    if (searchPane) searchPane.style.display = tabId === "resources" ? "none" : "flex";
    // Hide advanced filter icon on non-resource tabs
    if (filterToggle) filterToggle.style.display = tabId === "resources" ? "" : "none";
    if (advancedOpen && tabId !== "resources") setAdvancedOpen(false);
    applyActiveFilter();
  };

  // Wire events
  quickInput.addEventListener("input", applyQuickFilter);
  if (nameInput) nameInput.addEventListener("input", applyStructuredFilter);
  if (labelInput) labelInput.addEventListener("input", applyStructuredFilter);
  if (statusSelect) statusSelect.addEventListener("change", applyStructuredFilter);
  if (textInput) textInput.addEventListener("input", function () {
    const tableId = TABLE_IDS[activeTab];
    if (!tableId || activeTab === "resources") return;
    const query = textInput.value.trim().toLowerCase();
    filterRows(tableId, function (row) {
      return !query || row.textContent.toLowerCase().indexOf(query) !== -1;
    });
  });

  // Toggle buttons
  if (searchToggle) {
    searchToggle.addEventListener("click", function (e) {
      e.stopPropagation();
      setSearchOpen(!searchOpen);
    });
  }
  if (filterToggle) {
    filterToggle.addEventListener("click", function (e) {
      e.stopPropagation();
      setAdvancedOpen(!advancedOpen);
    });
  }

  // Close popovers when clicking outside
  document.addEventListener("click", function (e) {
    const wrapper = document.querySelector(".lr-filter-wrapper");
    if (!wrapper) return;
    if (!wrapper.contains(e.target)) {
      if (searchOpen) setSearchOpen(false);
      if (advancedOpen) setAdvancedOpen(false);
    }
  });

  // Restore persisted state
  const state = loadState();
  if (state.name && nameInput) nameInput.value = state.name;
  if (state.label && labelInput) labelInput.value = state.label;
  if (state.status && statusSelect) statusSelect.value = state.status;
  if (state.quick) quickInput.value = state.quick;
  if (state.mode === "advanced") {
    setAdvancedOpen(true, true);
  } else if (state.mode === "search" || state.quick) {
    setSearchOpen(true, true);
  }
}
