function inject(){
  var injectionScript = document.createElement('script');
  injectionScript.type = 'text/javascript';
  injectionScript.innerHTML = `
  (function() {
    var XHR = XMLHttpRequest.prototype;
    var send = XHR.send;
    var open = XHR.open;
    XHR.open = function(method, url) {
        this.url = url; // the request url
        return open.apply(this, arguments);
    }
    XHR.send = function() {
        this.addEventListener('load', function() {
            if (this.url.includes("SearchConsoleAggReportUi/data/batchexecute")) {
console.log(">> inside load: this: ", this);
                var dataDOMElement = document.createElement('div');
                dataDOMElement.id = '__interceptedData';
                dataDOMElement.innerText = this.response;
                dataDOMElement.style.height = 0;
                dataDOMElement.style.overflow = 'hidden';
                document.body.appendChild(dataDOMElement);
            }
        });
        return send.apply(this, arguments);
    };
  })();
  `;

  document.head.prepend(injectionScript);
}

document.addEventListener("DOMContentLoaded", function(){
  inject();
});
