$(function() {
    var navSelector = "#toc";
    var $toc = $(navSelector);
    Toc.init($toc);
    $("body").scrollspy({
        target: navSelector
    });
});
