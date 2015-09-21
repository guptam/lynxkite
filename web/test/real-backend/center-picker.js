'use strict';

/* global protractor, by */
var lib = require('./test-lib.js');
var K = protractor.Key;

module.exports = function(fw) {
  var centers = lib.left.side.element(by.id('centers'));
  var simplePickButton = lib.left.side.element(by.id('simple-pick-button'));
  var advancedPickButton = lib.left.side.element(by.id('advanced-pick-button'));
  var simpleModeButton = lib.left.side.element(by.id('simple-mode-button'));
  var advancedModeButton = lib.left.side.element(by.id('advanced-mode-button'));

  fw.statePreservingTest(
    'test-example project in sampled view',
    'simple center selection',
    function() {
      // Automatic center selection.
      expect(centers.getAttribute('value')).toBe('0');
      expect(lib.visualization.vertexCounts(0)).toBe(3);
      expect(simplePickButton.getText()).toBe('Next');

      // Selection with filter.
      lib.left.setAttributeFilter('name', 'Eve');
      expect(centers.getAttribute('value')).toBe('0');
      expect(lib.visualization.vertexCounts(0)).toBe(1);
      expect(simplePickButton.getText()).toBe('Pick');
      simplePickButton.click();
      expect(lib.visualization.vertexCounts(0)).toBe(1);
      expect(simplePickButton.getText()).toBe('Next');
      lib.left.setAttributeFilter('name', '');

      // Manual center selection.
      centers.clear();
      centers.sendKeys('3', K.ENTER); // Isolated Joe.
      expect(lib.visualization.vertexCounts(0)).toBe(1);

      // Next button.
      expect(simplePickButton.getText()).toBe('Pick');
      simplePickButton.click();
      expect(simplePickButton.getText()).toBe('Next');
      expect(centers.getAttribute('value')).toBe('0');
      simplePickButton.click();
      expect(centers.getAttribute('value')).toBe('1');
      simplePickButton.click();
      expect(centers.getAttribute('value')).toBe('2');
      simplePickButton.click();
      expect(centers.getAttribute('value')).toBe('3');
      simplePickButton.click();
      expect(centers.getAttribute('value')).toBe('0');

      // Select "everything".
      centers.clear();
      centers.sendKeys('*', K.ENTER);
      expect(lib.visualization.vertexCounts(0)).toBe(4);

      // Clean up state.
      centers.clear();
      centers.sendKeys('0', K.ENTER);
      expect(lib.visualization.vertexCounts(0)).toBe(3);
    });

  fw.statePreservingTest(
    'test-example project in sampled view',
    'advanced center selection',
    function() {
      advancedModeButton.click();
      advancedPickButton.click();
      // TODO: Advanced test.
      simpleModeButton.click();
    });
};
