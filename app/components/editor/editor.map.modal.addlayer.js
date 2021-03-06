/*
 * (c) 2014 Boundless, http://boundlessgeo.com
 */
angular.module('gsApp.workspaces.layers.addtomap', [
  'ngGrid'
])
.controller('AddToMapLayerCtrl', ['workspace', 'map', 'reinstateVisibility', '$scope',
  '$rootScope', '$state', '$log', '$modalInstance', 'GeoServer',
  'AppEvent', 'layersListModel', '_', '$timeout',
    function(workspace, map, reinstateVisibility, $scope, $rootScope, $state, $log,
      $modalInstance, GeoServer, AppEvent, layersListModel, _, $timeout) {

      $scope.workspace = workspace;
      $scope.map = map;

      $scope.addSelectedToMap = function() {
        var mapInfo = {
          'name': map.name
        };
        mapInfo.layersToAdd = [];
        for (var k=0; k < $scope.layerSelections.length; k++) {
          var layer = $scope.layerSelections[k];
          mapInfo.layersToAdd.push({
            'name': layer.name,
            'workspace': layer.workspace
          });
        }
        GeoServer.map.layers.add($scope.workspace, mapInfo.name,
          mapInfo.layersToAdd).then(function(result) {
            if (result.success) {
              $scope.map.layers = reinstateVisibility($scope.map.layers, result.data);
              $scope.map.layer_count++;
              $rootScope.alerts = [{
                type: 'success',
                message: mapInfo.layersToAdd.length +
                  ' layer(s) added to map ' + mapInfo.name + '.',
                fadeout: true
              }];
              $scope.close('added');
            } else {
              $rootScope.alerts = [{
                type: 'danger',
                message: 'Layer(s) could not be added to map ' +
                  mapInfo.name + ': ' + result.data.message,
                details: result.data.trace,
                fadeout: true
              }];
            }
          });
      };

      $scope.close = function () {
        $modalInstance.close('close');
      };

      $scope.importDataToNewLayers = function() {
        $modalInstance.close('import');
      };

      $scope.addToLayerSelections = function (layer) {
        if (!layer.selected) {
          _.remove($scope.layerSelections,
            function(lyr) {
              return lyr.name===layer.name;
            });
        } else {
          $scope.layerSelections.push(layer);
        }
      };

      $scope.addAllToLayerSelections = function(add) {
        //clear the selections
        $scope.layerSelections.length = 0;
        for (var i = 0; i < $scope.layerOptions.ngGrid.filteredRows.length; i++) {
          var layer = $scope.layerOptions.ngGrid.filteredRows[i].entity;
          if (add && !layer.alreadyInMap) {
            layer.selected = true;
            $scope.layerSelections.push(layer);
          } else {
            layer.selected = false;
          }
        }
      }

      // Available Layers Table with custom checkbox

      var modalWidth = 800;
      $scope.gridWidth = {'width': modalWidth};


      $scope.opts = {
        paging: {
          pageSizes: [25, 50, 100],
          pageSize: 25,
          currentPage: 1
        },
        sort: {
          fields: ['name'],
          directions: ['asc']
        },
        filter: {
          filterText: ''
        }
      };

      $scope.layerSelections = [];

      $scope.layerOptions = {
        data: 'layers',
        enableCellSelection: false,
        filterOptions: $scope.opts.filter,
        enableRowSelection: false,
        enableCellEdit: false,
        enableRowReordering: false,
        jqueryUIDraggable: false,
        checkboxHeaderTemplate:
          '<input class="ngSelectionHeader" type="checkbox"' +
            'ng-model="allSelected" ng-change="toggleSelectAll(allSelected)"/>',
        int: function() {
          $log('done');
        },
        sortInfo: $scope.opts.sort,
        showSelectionCheckbox: false,
        selectWithCheckboxOnly: false,
        selectedItems: $scope.layerSelections,
        multiSelect: true,
        columnDefs: [
          {field: 'select', displayName: 'S', width: '24px',
          cellTemplate: '<div ng-if="!row.entity.alreadyInMap"' +
            'style="margin: 12px 0px 6px 6px; padding: 0;">' +
            '<input type="checkbox" ng-model="row.entity.selected" ' +
            'ng-click="addToLayerSelections(row.entity);"></div>',
          headerCellTemplate: '<input class="ngSelectionHeader" type="checkbox"' +
            'ng-model="$parent.allSelected" ng-change="addAllToLayerSelections($parent.allSelected)"/>'
          },
          {
            field: 'name', displayName: 'Layer', 
            cellTemplate:
              '<div class="grid-text-padding"' +
                'title="{{row.entity.name}}">' +
                '{{row.entity.name}}' +
              '</div>',
            width: '20%'
          },
          {field: 'title',
            displayName: 'Title',
            enableCellEdit: false,
            cellTemplate:
              '<div class="grid-text-padding"' +
                'alt="{{row.entity.description}}"' +
                'title="{{row.entity.description}}">' +
                '{{row.entity.title}}' +
              '</div>',
            width: '30%'
          },
          {field: 'inMap',
            displayName: 'Status',
            cellClass: 'text-center',
            cellTemplate:
              '<div class="grid-text-padding"' +
                'ng-show="row.entity.alreadyInMap">' +
              'In Map</div>',
            width: '10%',
            sortable: false
          },
          {field: 'modified.timestamp',
            displayName: 'Modified',
            cellClass: 'text-center',
            cellFilter: 'modified.timestamp',
            cellTemplate:
              '<div class="grid-text-padding"' +
                'ng-show="row.entity.modified">' +
              '{{ row.entity.modified.pretty }}</div>',
            width: '20%',
            sortable: false
          },
          {field: 'geometry',
            displayName: 'Type',
            cellClass: 'text-center',
            cellTemplate:
              '<div get-type ' +
                'geometry="{{row.entity.geometry}}">' +
              '</div>',
            width: '10%',
            sortable: false
          }
        ],
        enablePaging: true,
        enableColumnResize: false,
        showFooter: false,
        totalServerItems: 'totalServerItems',
        pagingOptions: $scope.opts.paging,
        useExternalSorting: true
      };

      $scope.$watch('opts', function(newVal, oldVal) {
        if (newVal && newVal !== oldVal) {
          $scope.refreshLayers();
        }
      }, true);

      var refreshTimer = null;
      $scope.refreshLayers = function() {
        if (refreshTimer) {
          $timeout.cancel(refreshTimer);
        }
        refreshTimer = $timeout(function() {
          $scope.serverRefresh();
        }, 800);
      };

      function disableExistingLayers () {
        // disable layers already in map
        for (var k=0; k < $scope.layers.length; k++) {
          var layer = $scope.layers[k];
          for (var j=0; j < map.layers.length; j++) {
            var mapLayer = map.layers[j];
            if (layer.name===mapLayer.name) {
              layer.alreadyInMap = true;
            }
          }
        }
      }

      $scope.serverRefresh = function() {
        if ($scope.workspace) {
          var opts = $scope.opts;
          GeoServer.layers.get(
            $scope.workspace,
            opts.paging.currentPage-1,
            opts.paging.pageSize,
            opts.sort.fields[0] + ':' +
              opts.sort.directions[0],
            opts.filter.filterText
          ).then(function(result) {
            if (result.success) {
              $scope.layers = result.data.layers;
              disableExistingLayers();
              if ($scope.layerOptions) {
                $scope.layerSelections.length = 0;
                $scope.layerOptions.$gridScope['allSelected'] = false;
              }
              $scope.totalServerItems = result.data.total;
              $scope.itemsPerPage = opts.paging.pageSize;
              $scope.totalItems = $scope.totalServerItems;
            } else {
              $rootScope.alerts = [{
                type: 'warning',
                message: 'Layers for workspace ' + $scope.workspace +
                  ' could not be loaded.',
                fadeout: true
              }];
            }
          });
        }
      };

    }]);
