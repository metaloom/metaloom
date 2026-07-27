$(function() {
    var navSelector = "#toc";
    var $toc = $(navSelector);
    if (!$toc.length) {
        return;
    }
    // Scope the TOC to the page's own content. With bootstrap-toc's default scope (body) it also
    // collects headings from the site chrome — the footer's "Explore" / "Documentation" /
    // "Project" column headings showed up in the docs sidebar. Those carry data-toc-skip as well,
    // but scoping is the fix that keeps working when new chrome is added.
    var $scope = $(".docs-main-content").first();
    Toc.init($scope.length ? { $nav: $toc, $scope: $scope } : $toc);
    $("body").scrollspy({
        target: navSelector
    });
});
