// SPDX-License-Identifier: MIT
// Copyright (c) 2026

/* global jQuery3 */

(function () {
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

  jQuery3(function () {
    jQuery3(document).on('init.dt', function (_event, settings) {
      const tableEl = settings && settings.nTable;
      if (!tableEl || tableEl.id !== 'lockable-resources') {
        return;
      }

      const dt = jQuery3(tableEl).DataTable();

      const selectedSet = new Set();

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
        ensureSelectAllCheckbox(headerRow, tableEl, dt, selectedSet);
      }

      initColumnFilters(tableEl, dt, new Set([1, 2, 3, 4, 5]));

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
        if (cb.checked) {
          selectedSet.add(name);
        } else {
          selectedSet.delete(name);
        }
        updateSelectAllCheckbox(tableEl);
        updateBulkBar(selectedSet);
      });

      // Persist selection across redraws/paging.
      jQuery3(tableEl).on('draw.dt', function () {
        syncCheckboxesFromSelection(tableEl, selectedSet);
        updateSelectAllCheckbox(tableEl);
        updateBulkBar(selectedSet);
      });
    });
  });
})();
