'use strict';

var K = protractor.Key;  // Short alias.
//var EC = protractor.ExpectedConditions;
var lib = require('../test-lib.js');

function checkExactlyOneCurrent() {
  // There is only one highlighted item:
  expect(element.all(by.css('span.find-highlight-current')).count()).toBe(1);
}

function checkCurrentHighlight(expectedPos) {
  checkExactlyOneCurrent();
  // Check correct item:
  var cssQuery = 'span.find-highlight.find-highlight-current.find-highlight-' + expectedPos;
  expect(element(by.css(cssQuery)).isDisplayed()).toBe(true);
}

module.exports = function(fw) {
  fw.statePreservingTest(
    'empty splash',
    'search box in help',
    function() {
      element(by.id('help-link')).click();
      // help is opened in a new tab, let's switch there
      lib.switchToWindow(1);
      var field = element(by.id('find-in-page-text'));
      expect(field.isDisplayed()).toBe(true);
      // Search for the phrase "user".
      field.click();
      field.sendKeys('user');
      // Expect more than 5 matches.
      expect(element.all(by.css('span.find-highlight')).count())
        .toBeGreaterThan(5);
      // The first one is selected:
      checkCurrentHighlight(0);
      // Move to next:
      field.sendKeys(K.ENTER);
      checkCurrentHighlight(1);
      // Move to next:
      element(by.id('find-in-page-next')).click();
      checkCurrentHighlight(2);
      // Move to prev:
      element(by.id('find-in-page-prev')).click();
      checkCurrentHighlight(1);
      // Move to prev:
      element(by.id('find-in-page-prev')).click();
      checkCurrentHighlight(0);
      // Move to prev:
      element(by.id('find-in-page-prev')).click();
      checkExactlyOneCurrent();
      // Move to next:
      element(by.id('find-in-page-next')).click();
      checkCurrentHighlight(0);
      // Text not found:
      field.click();
      field.sendKeys('qwertyui');
      expect(element.all(by.css('span.find-highlight')).count()).toBe(0);
      // Close help tab and switch back to main tab.
      browser.driver.close();
      lib.switchToWindow(0);
    });

};