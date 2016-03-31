// The toolbox shows the list of operation categories and the operations.
'use strict';

angular.module('biggraph').directive('operationToolbox', function() {
  return {
    restrict: 'E',
    // A lot of internals are exposed, because this directive is used both in
    // side-operation-toolbox and in project-history.
    scope: {
      categories: '=',  // (Input.) List of operation categories.
      op: '=?',  // (Input/output.) Currently selected operation's id (if any).
      params: '=?',  // (Input/output.) Currently set operation parameters.
      category: '=?',  // (Input/output.) Currently selected category (if any).
      searching: '=?',  // (Input/output.) Whether operation search is active.
      applying: '=',  // (Input.) Whether an operation is just being submitted.
      editable: '=',  // (Input.) Whether the toolbox should be interactive.
      sideWorkflowEditor: '=',  // (Input/output.) The workflow editor available on this side.
      historyMode: '=',  // (Input.) Whether this toolbox is inside the history browser.
    },
    templateUrl: 'operation-toolbox.html',
    link: function(scope, elem) {
      scope.$watch('params', function(params) {
        if (params === undefined) { return; }
        params.withoutOptionalDefaults = function() {
          var params = angular.extend({}, scope.params); // Shallow copy.
          delete params.withoutOptionalDefaults; // This is not really a parameter, sorry.
          for (var i = 0; i < scope.opMeta.parameters.length; ++i) {
            var param = scope.opMeta.parameters[i];
            if (!param.mandatory && params[param.id] === param.defaultValue) {
              delete params[param.id];
            }
          }
          return params;
        };
      });

      scope.$watch('categories', function(cats) {
        // The complete list, for searching.
        scope.allOps = [];
        for (var i = 0; i < cats.length; ++i) {
          scope.allOps = scope.allOps.concat(cats[i].ops);
        }
      });

      scope.$watch('searching && !op', function(search) {
        if (search) {
          var filter = elem.find('#filter');
          filter.focus();
          scope.searchSelection = 0;
        }
      });

      scope.filterKey = function(e) {
        if (!scope.searching || scope.op) { return; }
        var operations = elem.find('.operation');
        if (e.keyCode === 38) { // UP
          e.preventDefault();
          scope.searchSelection -= 1;
          if (scope.searchSelection >= operations.length) {
            scope.searchSelection = operations.length - 1;
          }
          if (scope.searchSelection < 0) {
            scope.searchSelection = 0;
          }
        } else if (e.keyCode === 40) { // DOWN
          e.preventDefault();
          scope.searchSelection += 1;
          if (scope.searchSelection >= operations.length) {
            scope.searchSelection = operations.length - 1;
          }
        } else if (e.keyCode === 13) { // ENTER
          e.preventDefault();
          var op = angular.element(operations[scope.searchSelection]).scope().op;
          scope.clickedOp(op);
        }
      };

      scope.findColor = function(opId) {
        var op = scope.findOp(opId);
        for (var i = 0; i < scope.categories.length; ++i) {
          var cat = scope.categories[i];
          if (op.category === cat.title) {
            return cat.color;
          }
        }
        console.error('Could not find category for', opId);
        return 'yellow';
      };

      scope.findOp = function(opId) {
        for (var i = 0; i < scope.categories.length; ++i) {
          for (var j = 0; j < scope.categories[i].ops.length; ++j) {
            var op = scope.categories[i].ops[j];
            if (opId === op.id) {
              return op;
            }
          }
        }
        return undefined;
      };

      scope.clickedCat = function(cat) {
        if (scope.category === cat && !scope.op) {
          scope.category = undefined;
        } else {
          scope.category = cat;
        }
        scope.searching = undefined;
        scope.op = undefined;
      };
      scope.clickedOp = function(op) {
        if (op.status.enabled) {
          scope.op = op.id;
          scope.params = {};
        }
      };
      scope.searchClicked = function() {
        if (scope.searching) {
          scope.searching = undefined;
          scope.op = undefined;
        } else {
          startSearch();
        }
      };
      scope.$on('open operation search', startSearch);
      function startSearch() {
        scope.op = undefined;
        scope.category = undefined;
        scope.searching = true;
      }
    },
  };
});
