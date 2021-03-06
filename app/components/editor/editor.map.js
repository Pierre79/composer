/*
 * (c) 2014 Boundless, http://boundlessgeo.com
 *
 * editor.map.js, editor.map.less, editor.map.tpl.html
 * Also uses editor.less for styling shared with editor.layer.tpl.html
 *
 * Map view of the style editor. Sets up the map context and provides links to map, layer, and workspace modals
 * Also includes some functionality for showing/hiding layers (in conjunction with layerlist.js).
 *
 * NOTE: This module should only contain logic specific to the map veiw. 
 * General editor or map functionality should go in styleeditor.js or olmap.js respectively.
 */
angular.module('gsApp.editor.map', [
  'ui.codemirror',
  'ui.sortable',
  'gsApp.editor.olmap',
  'gsApp.editor.layerlist',
  'gsApp.editor.styleeditor',
  'gsApp.editor.tools.shortcuts',
  'gsApp.editor.tools.save',
  'gsApp.editor.tools.undo',
  'gsApp.editor.tools.layers',
  'gsApp.editor.tools.color',
  'gsApp.editor.tools.icons',
  'gsApp.editor.tools.attributes',
  'gsApp.editor.tools.display',
  'gsApp.editor.tools.sld',
  'gsApp.editor.tools.fullscreen',
  'gsApp.alertpanel',
  'gsApp.featureinfopanel',
  'gsApp.import',
  'gsApp.workspaces.maps.layerremove'
])
.config(['$stateProvider',
    function($stateProvider) {
      $stateProvider.state('editmap', {
        url: '/editmap/:workspace/:name',
        templateUrl: '/components/editor/editor.map.tpl.html',
        controller: 'MapComposeCtrl',
        params: { workspace: '', name: '', hiddenLayers: {} }
      });
    }])
.controller('MapComposeCtrl',
    ['$document', '$log', '$modal', '$rootScope', '$scope', '$state', '$stateParams', '$timeout', '$window',
    '_', 'AppEvent', 'GeoServer', 
    function($document, $log, $modal, $rootScope, $scope, $state, $stateParams, $timeout, $window,
      _, AppEvent, GeoServer) {

      var wsName = $stateParams.workspace;
      var mapName = $stateParams.name;
      var hiddenLayers = $stateParams.hiddenLayers;

      if (hiddenLayers && typeof hiddenLayers === 'string') {
        hiddenLayers = hiddenLayers.split(',');
      }

      /** WARNING: Editor scope variables **/
      /* The $scope of the editor pages is shared between editor.map / editor.layer, 
       * olmap, layerlist, and styleeditor. As such, care must be taken when adding
       * or modifying these scope variables.
       * See app/components/editor/README.md for more details.
       */
      $scope.workspace = wsName;
      $scope.layer = null;
      $scope.map = null
      $scope.mapOpts = null;
      $scope.isRendering = false;
      $scope.ysldstyle = null;
      

      //todo - hide sidenav
      $rootScope.$broadcast(AppEvent.ToggleSidenav);

      GeoServer.map.get(wsName, mapName).then(function(result) {
        if (result.success) {
          var map = result.data;
          $scope.map = map;

          //get the detailed version of the layers
          GeoServer.map.layers.get(wsName, map.name).then(
            function(result) {
              if (result.success) {
                map.layers = result.data;
                $scope.layer = map.layers.length > 0 ? map.layers[0] : null;

                // map options, extend map obj and add visible flag to layers
                $scope.mapOpts = angular.extend(map, {
                  layers: map.layers.map(function(l) {
                    l.visible = true;
                    if (hiddenLayers) { // reinstate visibility
                      var found = _.contains(hiddenLayers, l.name);
                      if (found) {
                        l.visible = false;
                      }
                    }
                    return l;
                  }),
                  error: function(err) {
                    if (err && typeof err == 'string' && err.lastIndexOf("Delays are occuring in rendering the map.", 0) === 0) {
                      $scope.$apply(function() {
                        $rootScope.alerts = [{
                          type: 'warning',
                          message: 'Map rendering may take a while...',
                          details: err,
                          fadeout: true
                        }];
                      });
                    } else {
                      $scope.$apply(function() {
                        $rootScope.alerts = [{
                          type: 'danger',
                          message: 'Map rendering error',
                          details: err.exceptions ? err.exceptions[0].text : err,
                          fadeout: true
                        }];
                      });
                    }
                  },
                  progress: function(state) {
                    if (state == 'start') {
                      $scope.isRendering = true;
                    }
                    if (state == 'end') {
                      $scope.isRendering = false;
                    }
                    $scope.$apply();
                  },
                  featureInfo: function(features) {
                    $scope.$broadcast('featureinfo', features);
                  }
                });

                if ($scope.layer) {
                  GeoServer.style.get(wsName, $scope.layer.name).then(function(result) {
                    if (result.success == true) {
                      $scope.ysldstyle = result.data;
                    } else {
                      $rootScope.alerts = [{
                        type: 'danger',
                        message: 'Could not retrieve style for layer: ' + $scope.layer.name
                      }];
                    }
                  });
                }
              } else {
                $rootScope.alerts = [{
                  type: 'danger',
                  message: 'Could not load layers for map ' + mapName + ': ' +
                    result.data.message,
                  details: result.data.trace,
                  fadeout: true
                }];
              }
            });
        } else {
          $rootScope.alerts = [{
            type: 'danger',
            message: 'Could not load map ' + mapName + ': ' +
              result.data.message,
            details: result.data.trace,
            fadeout: true
          }];
        }
      });

      $scope.viewWorkspace = function(workspace) {
        $rootScope.workspace = workspace;
        $state.go('workspace', {workspace: workspace});
      };

      // Save checkbox state as url parameters
      $scope.getHiddenLayers = function() {
        var hiddenLayers = _.remove($scope.map.layers,
          function(lyr) { return lyr.visible===false; });
        hiddenLayers = _.map(hiddenLayers,
          function(layer) { return layer.name; });
        return hiddenLayers.join();
      };

      $scope.reinstateVisiblility = function (prevLayers, newLayers) {
        for (var j=0; j < newLayers.length; j++) {
          var newLayer = newLayers[j];
          var prevLayer = _.find(prevLayers, function(prevLayer) {
            return newLayer.name===prevLayer.name;
          });
          if (prevLayer) {
            newLayer.visible = prevLayer.visible;
          } else {
            newLayer.visible = true;
          }
        }
        return newLayers;
      }

      $scope.addMapLayer = function(workspace) {
        var modalInstance = $modal.open({
          templateUrl: '/components/editor/editor.map.modal.addlayer.tpl.html',
          controller: 'AddToMapLayerCtrl',
          size: 'lg',
          resolve: {
            map: function() {
              return $scope.map;
            },
            workspace: function() {
              return $scope.workspace;
            },
            reinstateVisibility: function() {
              return $scope.reinstateVisiblility;
            }
          }
        }).result.then(function(response, args) {
          if (response==='import') {
            $scope.map.hiddenLayers = $scope.getHiddenLayers();

            $modal.open({
              templateUrl: '/components/import/import.tpl.html',
              controller: 'DataImportCtrl',
              backdrop: 'static',
              size: 'lg',
              resolve: {
                workspace: function() {
                  return $scope.workspace;
                },
                mapInfo: function() {
                  return $scope.map;
                },
                contextInfo: function() {
                  return {
                    title:'Import Layers into <i class="icon-map"></i> <strong>'+$scope.map.name+'</strong>',
                    hint:'Add selected layers to map '+$scope.map.name,
                    button:'Add layers to map'
                  };
                }
              }
            }).result.then(function(layers) {
              if (layers) {
                //Add returned layers to map
                GeoServer.map.layers.add($scope.workspace, $scope.map.name,
                  layers).then(function(result) {
                    if (result.success) {
                      $scope.map.layers = $scope.reinstateVisiblility($scope.map.layers, result.data);
                      $scope.map.layer_count++;
                      $scope.refreshMap();
                      $rootScope.alerts = [{
                        type: 'success',
                        message: layers.length +
                          ' layer(s) added to map ' + $scope.map.name + '.',
                        fadeout: true
                      }];
                    } else {
                      $rootScope.alerts = [{
                        type: 'danger',
                        message: 'Layer(s) could not be added to map ' +
                          $scope.map.name + ': ' + result.data.message,
                        details: result.data.trace,
                        fadeout: true
                      }];
                    }
                  });
              }
            });
          } else if (response==='added') {
            $scope.refreshMap();
          }
        });
      };

      $scope.editLayerSettings = function(layer) {
        var modalInstance = $modal.open({
          templateUrl: '/components/modalform/layer/layer.settings.tpl.html',
          controller: 'EditLayerSettingsCtrl',
          backdrop: 'static',
          size: 'md',
          resolve: {
            workspace: function() {
              return layer.workspace;
            },
            layer: function() {
              return layer;
            }
          }
        });
      };

      $scope.editMapSettings = function(map) {
        var modalInstance = $modal.open({
          templateUrl: '/components/modalform/map/map.settings.tpl.html',
          controller: 'EditMapSettingsCtrl',
          backdrop: 'static',
          size: 'md',
          resolve: {
            workspace: function() {
              return $scope.workspace;
            },
            map: function() {
              return map;
            }
          }
        });
      };

      $scope.hideCtrl = {
        'all': false,
        'lonlat': false
      };

      $scope.$on(AppEvent.MapControls, function(scope, ctrl) {
        var val = $scope.hideCtrl[ctrl];
        if (ctrl &&  val !== undefined) {
          $scope.hideCtrl[ctrl] = !val;
        }
      });

      $rootScope.$on(AppEvent.MapUpdated, function(scope, map) {
        if ($scope.map.name == map.original.name) {
          for (var i = 0; i < $scope.mapOpts.layers.length; i++) {
            if (map.original.layers[i].name == $scope.mapOpts.layers[i].name) {
              map.new.layers[i].visible = $scope.mapOpts.layers[i].visible
               $scope.mapOpts.layers[i] = map.new.layers[i];
            }
          }
          $scope.map = map.new;

          if (map.new.name != map.original.name) {
            $scope.mapOpts.name = map.new.name;
          }
          

          if (map.new.proj != map.original.proj) {
            $scope.mapOpts.proj = map.new.proj;
          }
          if (map.new.bbox != map.original.bbox) {
            $scope.mapOpts.bbox = map.new.bbox;
          }
        }
      }); 

      $rootScope.$on(AppEvent.LayerUpdated, function(scope, layer) {
        for (var i = 0; i < $scope.mapOpts.layers.length; i++) {
          if (layer.original.name == $scope.mapOpts.layers[i].name) {

            layer.new.visible = $scope.mapOpts.layers[i].visible;
            $scope.map.layers[i] = layer.new;
            $scope.mapOpts.layers[i] = layer.new;

            if ($scope.layer.name = layer.original.name) {
              $scope.layer = layer.new;
            }
          }
          
        }
      });

      $scope.toggleFullscreen = function() {
        $rootScope.broadcast(AppEvent.ToggleFullscreen);
      };

      $scope.onUpdatePanels = function() {
        $rootScope.$broadcast(AppEvent.SidenavResized); // update map
      };

    }]);
