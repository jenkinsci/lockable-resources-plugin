// SPDX-License-Identifier: MIT
// Copyright (c) 2020, Tobias Gruetzmacher

/* global jQuery3 */

(function () {
  function initColumnFilters(tableEl, api) {
    if (!tableEl || !api) {
      return;
    }

    const inputs = tableEl.querySelectorAll('input.lockable-resources-column-filter[data-dt-column]');
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

  // Prefer init.dt (fires when DataTables initializes), but also handle the case where
  // DataTables initialized before this script attached.
  jQuery3(function () {
    jQuery3(document).on('init.dt', 'table.data-table', function (_event, settings) {
      const tableEl = settings && settings.nTable;
      if (!tableEl || tableEl.id !== 'lockable-resources-labels') {
        return;
      }

      try {
        const api = new jQuery3.fn.dataTable.Api(settings);
        initColumnFilters(tableEl, api);
      } catch (e) {
        // Don't break the page.
        // eslint-disable-next-line no-console
        console.warn('[LR] Failed to initialize labels column filters', e);
      }
    });

    // If the table was initialized before we attached the init handler.
    try {
      const tableEl = document.getElementById('lockable-resources-labels');
      if (!tableEl) {
        return;
      }
      if (!jQuery3.fn.dataTable || !jQuery3.fn.dataTable.isDataTable) {
        return;
      }
      if (jQuery3.fn.dataTable.isDataTable(tableEl)) {
        initColumnFilters(tableEl, jQuery3(tableEl).DataTable());
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn('[LR] Failed to initialize existing labels column filters', e);
    }
  });
})();
