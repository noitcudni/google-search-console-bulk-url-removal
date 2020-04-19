console.log("hello! from inject");

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

// [["wrb.fr","mE1oA","[\"https://polymorphiclabs.io/\",[[\"0000000000000000/110687702074834780045/0005a397878c102a/srv-is\",\"https://polymorphiclabs.io/foo\",1,1587246093046914,1,1,false,0]\

// ["wrb.f["wrb.fr","vm16b",null,null,null,[6,null,[["type.googleapis.com/social.frontend.searchconsole.data.SubmitRemovalError",[3]↵]↵]↵]↵,"generic"]↵,["di",292]↵,["af.httprm",291,"-3391450240536993335",30]↵]↵26↵[["e",4,null,null,239]r","vm16b",null,null,null,[6,null,[["type.googleapis.com/social.frontend.searchconsole.data.SubmitRemovalError",[3]↵]↵]↵]↵,"generic"]↵,["di",292]↵,["af.httprm",291,"-3391450240536993335",30]↵]↵26↵[["e",4,null,null,239]

// response: ")]}'↵↵112↵[["wrb.fr","vm16b","[]\n",null,null,null,"generic"]↵,["di",261]↵,["af.httprm",260,"-7164925716848719425",28]↵]↵26↵[["e",4,null,null,149]↵]↵"


document.addEventListener("DOMContentLoaded", function(){
  inject();
});
