// SPDX-License-Identifier: MIT
// Copyright (c) 2020, Tobias Gruetzmacher

// Jenkins core used to expose a global `crumb` helper. Newer Jenkins pages expose
// crumb data via <head data-crumb-header/data-crumb-value> instead.
// The Stapler JS proxy (org/kohsuke/stapler/bind.js) and this plugin both rely on
// `crumb.wrap(...)` to attach the CSRF crumb to POST requests.
// Create a minimal compatibility shim if it's missing.
(function () {
  if (typeof window.crumb !== 'undefined') {
    return;
  }
  const head = document.querySelector('head');
  if (!head) {
    return;
  }
  const header = head.getAttribute('data-crumb-header');
  const value = head.getAttribute('data-crumb-value');
  if (!header || !value) {
    return;
  }
  window.crumb = {
    wrap: function (headers) {
      const merged = headers ? Object.assign({}, headers) : {};
      merged[header] = value;
      return merged;
    }
  };
})();

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

function find_resource_name(element) {
  // With async-loaded DataTables rows, events are delegated and there may be no stable
  // <tr data-resource-name> attribute. Prefer an explicit data-resource-name on the
  // clicked element.
  if (element && element.dataset && element.dataset.resourceName) {
    return element.dataset.resourceName;
  }
  var host = element && element.closest ? element.closest('[data-resource-name]') : null;
  return host ? host.getAttribute('data-resource-name') : null;
}


function resource_action(button, action) {
  var resourceName = find_resource_name(button);
  if (!resourceName) {
    console.warn("[LR] Could not resolve resourceName for action", action);
    return;
  }
  console.log("[LR] resource_action called:", action, resourceName);

  // For reserve and steal actions, prompt for a reason
  if (action === "reserve" || action === "steal") {
    console.log("[LR] " + action + " action - showing dialog");
    var titleKey = action + "-title";
    var messageKey = action + "-message";
    dialog
      .prompt(i18n(titleKey, resourceName), {
        message: i18n(messageKey, resourceName),
        minWidth: "450px",
        maxWidth: "600px"
      })
      .then(
        (reason) => {
          console.log("[LR] dialog resolved with reason:", reason);
          var url = action + "?resource=" + encodeURIComponent(resourceName) + "&reason=" + encodeURIComponent(reason || "");
          console.log("[LR] fetching:", url);
          fetch(url,
            {
              method: "post",
              headers: crumb.wrap({}),
            },
          ).then((rsp) => {
            console.log("[LR] fetch response:", rsp.status, rsp.ok);
            showResponse(rsp, i18n("action-on-success", action, resourceName), i18n("action-on-fail", action, resourceName));
            if (!rsp.ok) {
              window.location.reload();
            }
          }).catch((err) => {
            console.error("[LR] fetch error:", err);
          });
        },
        (cancelled) => {
          console.log("[LR] dialog cancelled:", cancelled);
        }
      );
    return;
  }

  fetch(action + "?resource=" + encodeURIComponent(resourceName),
    {
      method: "post",
      headers: crumb.wrap({}),
    },
  ).then((rsp) => {
    showResponse(rsp, i18n("action-on-success", action, resourceName), i18n("action-on-fail", action, resourceName));
    if (!rsp.ok) {
      window.location.reload();
    }
  });
}

function replaceNote(resourceName) {
  var d = document.getElementById("note-" + resourceName);
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
        d.getElementsByTagName("TEXTAREA")[0].focus();
      });
      layoutUpdateCallback.call();
    });
  });
}

function i18n(messageId, arg0, arg1) {
  return document.querySelector("#i18n").getAttribute("data-" + messageId).replace("{0}", arg0).replace("{1}", arg1);
}

function getSelectedResources() {
  if (window.lockableResourcesSelection && typeof window.lockableResourcesSelection.getSelected === 'function') {
    return window.lockableResourcesSelection.getSelected();
  }
  return [];
}

function bulk_resource_action(action) {
  const selected = getSelectedResources();
  if (!selected.length) {
    return;
  }

  const summaryName = selected.length === 1 ? selected[0] : (selected.length + " resources");

  function run(reason) {
    let okCount = 0;
    let failCount = 0;

    return selected.reduce(
      (p, resourceName) => p.then(() => {
        let url = action + "?resource=" + encodeURIComponent(resourceName);
        if (reason !== undefined && reason !== null) {
          url += "&reason=" + encodeURIComponent(reason || "");
        }
        return fetch(url, { method: "post", headers: crumb.wrap({}) }).then((rsp) => {
          if (rsp.ok) {
            okCount++;
          } else {
            failCount++;
          }
        });
      }),
      Promise.resolve()
    ).then(() => {
      if (failCount === 0) {
        notificationBar.show(i18n("action-on-success", action, summaryName), notificationBar.SUCCESS);
      } else {
        dialog.alert(i18n("action-on-fail", action, summaryName), {
          message: okCount + " succeeded, " + failCount + " failed"
        });
      }
      if (window.lockableResourcesSelection && typeof window.lockableResourcesSelection.clear === 'function') {
        window.lockableResourcesSelection.clear();
      }
      window.location.reload();
    });
  }

  if (action === "reserve" || action === "steal") {
    const titleKey = action + "-title";
    const messageKey = action + "-message";
    dialog
      .prompt(i18n(titleKey, summaryName), {
        message: i18n(messageKey, summaryName),
        minWidth: "450px",
        maxWidth: "600px"
      })
      .then((reason) => run(reason));
    return;
  }

  run(null);
}

function initDataTableColumnFilters(tableEl, api, tableId) {
  if (!tableEl || !api) {
    return;
  }

  const toolbar = tableId ? document.getElementById('toolbar-' + tableId) : null;
  const container = toolbar || tableEl;

  const inputs = container.querySelectorAll('input.lockable-resources-column-filter[data-dt-column]');
  if (!inputs || inputs.length === 0) {
    return;
  }

  inputs.forEach(function (input) {
    if (input.dataset.lrBound === 'true') {
      return;
    }

    const columnIndex = parseInt(input.dataset.dtColumn, 10);
    if (Number.isNaN(columnIndex)) {
      return;
    }

    // Initialize from restored state (stateSave).
    const existing = api.column(columnIndex).search();
    if (existing) {
      input.value = existing;
    }

    input.addEventListener('input', function () {
      api.column(columnIndex).search(input.value || '').draw();
    });

    // When filters are inside a <th>, prevent triggering sort.
    input.addEventListener('click', function (e) {
      e.stopPropagation();
    });

    input.dataset.lrBound = 'true';
  });
}

// Column filters for DataTables instances on LR pages.
// Prefer the init.dt event, but also handle tables that initialized before this script attached.
jQuery3(document).on('init.dt', 'table.data-table', function (_event, settings) {
  try {
    const tableEl = settings && settings.nTable;
    if (!tableEl) {
      return;
    }
    const api = new jQuery3.fn.dataTable.Api(settings);
    initDataTableColumnFilters(tableEl, api, settings.sTableId);
  } catch (e) {
    console.warn('[LR] Failed to initialize column filters', e);
  }
});

jQuery3(document).ready(function () {
  try {
    if (!jQuery3.fn.dataTable || !jQuery3.fn.dataTable.isDataTable) {
      return;
    }

    jQuery3('table.data-table').each(function () {
      if (!jQuery3.fn.dataTable.isDataTable(this)) {
        return;
      }
      const api = jQuery3(this).DataTable();
      initDataTableColumnFilters(this, api, this.id);
    });
  } catch (e) {
    console.warn('[LR] Failed to initialize existing column filters', e);
  }

  // Delegate clicks since table rows are loaded asynchronously.
  document.addEventListener("click", function (event) {
    const bulkButton = event.target.closest(".lockable-resources-bulk-action-button");
    if (bulkButton) {
      const action = bulkButton.dataset.action;
      bulk_resource_action(action);
      return;
    }

    const actionButton = event.target.closest(".lockable-resources-action-button");
    if (actionButton) {
      const action = actionButton.dataset.action;
      resource_action(actionButton, action);
      return;
    }

    const queueButton = event.target.closest(".lockable-resources-change-queue-order");
    if (queueButton) {
      changeQueueOrder(queueButton.dataset.queueItemId);
      return;
    }

    const noteLink = event.target.closest(".lockable-resources-replace-note");
    if (noteLink) {
      event.preventDefault();
      replaceNote(noteLink.dataset.resourceName);
    }
  });
});


