// Stream Goat's YouTube ad remover (the same approach as TizenTube): YouTube's TV app receives its ads inside the
// JSON it parses (adPlacements, playerAds, adSlots, masthead shelves). This removes them before the app sees them,
// so no ad is ever requested or shown. Runs before YouTube's own scripts.
(function () {
  if (window.__sgAdblock) return; window.__sgAdblock = true;
  var parse = JSON.parse;
  function clean(o) {
    if (!o || typeof o !== 'object') return o;
    if (o.adPlacements) o.adPlacements = [];
    if (o.playerAds) o.playerAds = [];
    if (o.adSlots) o.adSlots = [];
    if (o.adBreakHeartbeatParams) delete o.adBreakHeartbeatParams;
    if (o.playerResponse) clean(o.playerResponse);
    try {
      var s = o.contents && o.contents.tvBrowseRenderer && o.contents.tvBrowseRenderer.content &&
        o.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer &&
        o.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content &&
        o.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content.sectionListRenderer;
      if (s && s.contents) s.contents = s.contents.filter(function (c) { return !c.tvMastheadRenderer && !c.adSlotRenderer; });
      var shelves = function (list) {
        if (!list) return list;
        return list.filter(function (c) { return !c.adSlotRenderer && !c.tvMastheadRenderer; });
      };
      if (o.continuationContents && o.continuationContents.sectionListContinuation)
        o.continuationContents.sectionListContinuation.contents = shelves(o.continuationContents.sectionListContinuation.contents);
    } catch (e) {}
    return o;
  }
  JSON.parse = function () { var r = parse.apply(this, arguments); try { clean(r); } catch (e) {} return r; };
  // Responses read with fetch().json() go through JSON.parse too in this app; a safety net for Response.json:
  if (window.Response && Response.prototype.json) {
    var json = Response.prototype.json;
    Response.prototype.json = function () { return json.apply(this, arguments).then(function (r) { try { clean(r); } catch (e) {} return r; }); };
  }
})();
