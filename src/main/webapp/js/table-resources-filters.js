// SPDX-License-Identifier: MIT
// Copyright (c) 2026

/* global jQuery3 */

(function () {
  function enhanceResourcesTable(tableEl, settings) {
    if (!tableEl || tableEl.id !== 'lockable-resources') {
      return;
    }

    if (tableEl.dataset.lrResourcesEnhanced === 'true') {
      return;
    }

    let dataTable;
    try {
      if (settings) {
        dataTable = new jQuery3.fn.dataTable.Api(settings);
      } else {
        dataTable = jQuery3(tableEl).DataTable();
      }
    } catch (e) {
      // If DataTables hasn't initialized yet, we'll try again via init.dt.
      return;
    }

    tableEl.dataset.lrResourcesEnhanced = 'true';

    const selectedSet = new Set();
    let lastClickedResourceName = null;

    function setSelected(resourceName, selected) {
      if (!resourceName) {
        return;
      }
      if (selected) {
        selectedSet.add(resourceName);
      } else {
        selectedSet.delete(resourceName);
      }
    }

    function setCheckbox(cb, selected) {
      if (!cb) {
        return;
      }
      cb.checked = selected;
      setSelected(cb.getAttribute('data-resource-name'), selected);
    }

    // Expose selection to other scripts (bulk action handler).
    window.lockableResourcesSelection = {
      getSelected: function () {
        return Array.from(selectedSet);
      },
      clear: function () {
        selectedSet.clear();
        syncCheckboxesFromSelection(tableEl, selectedSet);
        updateSelectAllCheckbox(tableEl);
        updateBulkBar(selectedSet);
      }
    };

    const headerRow = tableEl.querySelector('thead tr');
    if (headerRow) {
      ensureSelectAllCheckbox(headerRow, tableEl, dataTable, selectedSet);
    }

    // Only filter the primary "Resource" column.
    initColumnFilters(tableEl, dataTable, new Set([2]));

    updateBulkBar(selectedSet);

    // Track selection changes (delegated).
    tableEl.addEventListener('change', function (e) {
      const cb = e.target && e.target.closest ? e.target.closest('input.lockable-resources-select[data-resource-name]') : null;
      if (!cb) {
        return;
      }
      const name = cb.getAttribute('data-resource-name');
      if (!name) {
        return;
      }
      setSelected(name, cb.checked);
      updateSelectAllCheckbox(tableEl);
      updateBulkBar(selectedSet);
    });

    // Improve multi-row selection:
    // - shift+click a checkbox to select a range (current page only)
    // - click a row (non-interactive area) to toggle its checkbox
    tableEl.addEventListener('click', function (e) {
      const checkbox = e.target && e.target.closest
        ? e.target.closest('input.lockable-resources-select[data-resource-name]')
        : null;

      if (checkbox) {
        const currentName = checkbox.getAttribute('data-resource-name');
        if (e.shiftKey && lastClickedResourceName && currentName) {
          const cbs = Array.from(
            tableEl.querySelectorAll('tbody input.lockable-resources-select[data-resource-name]')
          );
          const fromIndex = cbs.findIndex(function (x) {
            return x.getAttribute('data-resource-name') === lastClickedResourceName;
          });
          const toIndex = cbs.findIndex(function (x) {
            return x.getAttribute('data-resource-name') === currentName;
          });

          if (fromIndex >= 0 && toIndex >= 0) {
            const start = Math.min(fromIndex, toIndex);
            const end = Math.max(fromIndex, toIndex);
            const selected = checkbox.checked;
            for (let i = start; i <= end; i++) {
              setCheckbox(cbs[i], selected);
            }
            updateSelectAllCheckbox(tableEl);
            updateBulkBar(selectedSet);
          }
        }

        if (currentName) {
          lastClickedResourceName = currentName;
        }
        return;
      }

      // Ignore clicks on interactive elements.
      if (e.target && e.target.closest && e.target.closest('a,button,input,select,textarea,label')) {
        return;
      }

      const row = e.target && e.target.closest ? e.target.closest('tbody tr') : null;
      if (!row) {
        return;
      }

      const rowCb = row.querySelector('input.lockable-resources-select[data-resource-name]');
      if (!rowCb) {
        return;
      }

      const selected = !rowCb.checked;
      setCheckbox(rowCb, selected);
      lastClickedResourceName = rowCb.getAttribute('data-resource-name');
      updateSelectAllCheckbox(tableEl);
      updateBulkBar(selectedSet);
    });

    // Persist selection across redraws/paging.
    jQuery3(tableEl).on('draw.dt', function () {
      syncCheckboxesFromSelection(tableEl, selectedSet);
      updateSelectAllCheckbox(tableEl);
      updateBulkBar(selectedSet);
    });
  }

  function updateBulkBar(selectedSet) {
    const bar = document.getElementById('lockable-resources-bulk-bar');
    if (!bar) {
      return;
    }

    const count = selectedSet.size;
    const countEl = document.getElementById('lockable-resources-selected-count');
    if (countEl) {
      countEl.textContent = String(count);
    }

    const hidden = count === 0;
    bar.setAttribute('aria-hidden', hidden ? 'true' : 'false');
    bar.querySelectorAll('button.lockable-resources-bulk-action-button').forEach(function (btn) {
      btn.disabled = hidden;
    });
  }

  function ensureSelectAllCheckbox(headerRow, tableEl, dataTable, selectedSet) {
    const firstTh = headerRow.querySelector('th');
    if (!firstTh) {
      return;
    }
    if (firstTh.querySelector('input.lockable-resources-select-all')) {
      return;
    }

    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.className = 'lockable-resources-select-all';
    cb.setAttribute('aria-label', 'Select all');

    cb.addEventListener('click', function (e) {
      // Do not trigger sort.
      e.stopPropagation();
    });

    cb.addEventListener('change', function () {
      const checked = cb.checked;

      // Only affect currently rendered rows (current page).
      tableEl.querySelectorAll('tbody input.lockable-resources-select[data-resource-name]').forEach(function (rowCb) {
        const name = rowCb.getAttribute('data-resource-name');
        if (!name) {
          return;
        }
        rowCb.checked = checked;
        if (checked) {
          selectedSet.add(name);
        } else {
          selectedSet.delete(name);
        }
      });

      updateBulkBar(selectedSet);
    });

    firstTh.textContent = '';
    firstTh.appendChild(cb);
  }

  function syncCheckboxesFromSelection(tableEl, selectedSet) {
    tableEl.querySelectorAll('tbody input.lockable-resources-select[data-resource-name]').forEach(function (rowCb) {
      const name = rowCb.getAttribute('data-resource-name');
      rowCb.checked = !!(name && selectedSet.has(name));
    });
  }

  function updateSelectAllCheckbox(tableEl) {
    const headerCb = tableEl.querySelector('thead input.lockable-resources-select-all');
    if (!headerCb) {
      return;
    }
    const rowCbs = Array.from(tableEl.querySelectorAll('tbody input.lockable-resources-select[data-resource-name]'));
    if (rowCbs.length === 0) {
      headerCb.checked = false;
      headerCb.indeterminate = false;
      return;
    }

    const checkedCount = rowCbs.filter(function (cb) { return cb.checked; }).length;
    headerCb.checked = checkedCount === rowCbs.length;
    headerCb.indeterminate = checkedCount > 0 && checkedCount < rowCbs.length;
  }

  function initColumnFilters(tableEl, dataTable, filterableColumns) {
    const thead = tableEl.querySelector('thead');
    if (!thead) {
      return;
    }

    if (thead.querySelector('tr.lockable-resources-filters')) {
      return; // already initialized
    }

    const headerRow = thead.querySelector('tr');
    if (!headerRow) {
      return;
    }

    const headerCells = Array.from(headerRow.querySelectorAll('th'));
    const filterRow = document.createElement('tr');
    filterRow.className = 'lockable-resources-filters';

    headerCells.forEach(function (th, idx) {
      const filterTh = document.createElement('th');

      if (!filterableColumns.has(idx)) {
        filterTh.textContent = '';
        filterRow.appendChild(filterTh);
        return;
      }

      const input = document.createElement('input');
      input.type = 'search';
      input.className = 'form-control form-control-sm';
      input.placeholder = (th.textContent || '').trim();

      const existing = dataTable.column(idx).search();
      if (existing) {
        input.value = existing;
      }

      input.addEventListener('input', function () {
        dataTable.column(idx).search(input.value || '').draw();
      });

      input.addEventListener('click', function (e) {
        e.stopPropagation();
      });

      filterTh.appendChild(input);
      filterRow.appendChild(filterTh);
    });

    thead.appendChild(filterRow);
  }

  // Prefer init.dt (fires when DataTables initializes), but also handle the case where
  // DataTables initialized before this script attached.
  jQuery3(document).on('init.dt', function (_event, settings) {
    try {
      const tableEl = settings && settings.nTable;
      enhanceResourcesTable(tableEl, settings);
    } catch (e) {
      // Don't break the page.
      console.warn('[LR] Failed to enhance resources table on init.dt', e);
    }
  });

  jQuery3(document).ready(function () {
    try {
      const tableEl = document.getElementById('lockable-resources');
      if (!tableEl) {
        return;
      }
      if (!jQuery3.fn.dataTable || !jQuery3.fn.dataTable.isDataTable) {
        return;
      }
      if (jQuery3.fn.dataTable.isDataTable(tableEl)) {
        enhanceResourcesTable(tableEl);
      }
    } catch (e) {
      console.warn('[LR] Failed to enhance existing resources table', e);
    }
  });
})();
