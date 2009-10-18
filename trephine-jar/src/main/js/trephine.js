/**
 * trephine.js
 * (c) Jim R. Wilson 2009
 */
var trephine = new function(){
	this.loaded = false;
	this.load = function(options) {
		options = options || {};
		if (this.loaded && !options.force) return;
		var div = document.createElement('div');
		div.innerHTML = '<iframe id="trephine_iframe" src="about:blank" style="width:0px;height:0px;border:none"></iframe>';
		var iframe = this.iframe = div.getElementsByTagName('iframe')[0], loaded = false, self = this;
		iframe.style.position = 'absolute';
		iframe.style.left = '50%';
		iframe.style.top = '50%';
		iframe.onload = iframe.onreadystatechange = function() {
			if (loaded) return; else loaded = true;
			var doc = iframe.contentWindow || iframe.contentDocument, doc = doc.document || doc;
			var jarroot = options.root || '';
			var http = (/^https?:/).test(document.location);
			var archive = [jarroot + 'trephine.jar' + (http ? '?' + Math.random() : '')];
			if (options.jars) {
				for (var i=0; i<options.jars.length; i++) {
					var jar = options.jars[i];
					if (jar.match(new RegExp('^(?:(?:https?|s?ftp|file)://|/)'))) archive.push(jar);
					else archive.push(jarroot + jar);
				}
			}
			doc.open();
			doc.write([
				'<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">',
				'<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">',
				'<head><title>Trephine Applet</title></head><body>',
				'<applet id="trephine_applet" code="org.trephine.Launcher"',
				'archive="', archive.join(','), '" ',
				'width="0" height="0" mayscript="true">',
				'<param name="onload" value="parent.trephine._finishLoading" />',
				'<param name="onerror" value="parent.trephine._errorLoading" />',
				(options.debug ? '<param name="debug" value="true" />' : ''),
				(options.engines ? '<param name="engines" value="' + options.engines.join(',') + '" />' : ''),
				'</applet><script type="text/javascript">parent.trephine._afterApplet(document.getElementById("trephine_applet"));<',
				'/script></body></html>'
			].join(''));
			doc.close();
			self.applet = doc.getElementById('trephine_applet');
		};
		this._loadCallback = options.onload || function(){};
		this._finishLoading = function() {
			this.loaded = true;
			try {
				this.marshal = iframe.contentWindow.trephine.marshal;
				this.marshal.getVersion();
				this.handler = this.marshal;
			} catch (err) {
			  this.handler = this.applet;
			}
			if (this._loadCallback) {
				this._loadCallback(this);
				delete this._loadCallback;
			}
			delete this._finishLoading;
		};
		this._errorLoading = function() {
			if (this._errorCallback) {
					this._errorCallback(this);
				delete this._errorCallback;
			}
			delete this._errorLoading;
		};
		this._errorCallback = options.onerror || function(){};
		this._afterApplet = function(applet) {
			if (this._errorCallback) {
				try {
					applet.getVersion();
				} catch(err) {
					this._errorCallback(this);
				}
				delete this._errorCallback;
			}
			delete this._afterApplet;
		};
		document.body.appendChild(iframe);
	};
	this.exec = function(lang, code){
		if (!this.handler) return null;
		var result = this.handler.exec(lang, code);
		return { success: result.get(0), result: result.get(1), error: result.get(2) };
	};
	this.isPrivileged = function(){ return (this.handler ? this.handler.isPrivileged() : null); };
	this.hasPermission = function(){ return (this.handler ? this.handler.hasPermission() : null); };
	this.askPermission = function( callback ){
		if (!this.handler) return;
		this._askCallback = callback || function(){};
		return this.handler.askPermission( 'parent.trephine._askCallback' );
	};
	this.isDebugEnabled = function(){ return (this.handler ? this.handler.isDebugEnabled() : null); };
	this.enableDebug = function(){ return (this.handler ? this.handler.enableDebug() : null); };
	this.version = function(){ return (this.handler ? this.handler.getVersion() : null); };
	this.js = function(code) {
		if (!code) return { success:false, result: null, error: 'Code was null' };
		if (typeof code!='function' && arguments.length<2) return trephine.exec('js', code);
		if (typeof code=='function') code = '(' + code + ')';
		else code = '(function(){' + code + '})';
		var args = []; for (var i=1; i<arguments.length; i++) args.push(this.toJSON(arguments[i]));
		code += '(' + args.join(',') + ')';
		return trephine.exec('js', code);
	};
	this.toJSON = (function(){
		var f = function (n) { return n < 10 ? '0' + n : n; };
		var methods = {
			'[object String]': function() { return this.valueOf(); },
			'[object Date]': function() {
				return [
					this.getUTCFullYear(), '-',
					f(this.getUTCMonth() + 1), '-',
					f(this.getUTCDate()), 'T',
					f(this.getUTCHours()), ':',
					f(this.getUTCMinutes()), ':',
					f(this.getUTCSeconds()), 'Z'
				].join('');
			}
		};
		methods['[object Boolean]'] = methods['[object Number]'] = methods['[object String]'];
		var toJSON = function(value){
			if (typeof value !== 'object') return { success: false };
			var type = Object.prototype.toString.apply(value);
			if (!methods[type]) return { success: false };
			return { success: true, value: methods[type].apply(value) };
		};
		var cx = new RegExp('[\u0000\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]', 'g'),
			escapable = new RegExp('[\\\\\"\x00-\x1f\x7f-\x9f\u00ad\u0600-\u0604\u070f\u17b4\u17b5\u200c-\u200f\u2028-\u202f\u2060-\u206f\ufeff\ufff0-\uffff]', 'g'),
			meta = { '\b': '\\b', '\t': '\\t', '\n': '\\n', '\f': '\\f', '\r': '\\r', '"' : '\\"', '\\': '\\\\' };
		var qcallback = function (a) {
			var c = meta[a];
			return typeof c === 'string' ? c : '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
		};
		var quote = function (string) {
			escapable.lastIndex = 0;
			return ['"', (escapable.test(string) ? string.replace(escapable, qcallback) : string), '"'].join('');
		}
		var str = function (key, holder) {
			var value = holder[key], result = toJSON(value);
			if (result.success) value = result.value;
			switch (typeof value) {
			case 'string': return quote(value);
			case 'number': return isFinite(value) ? String(value) : 'null';
			case 'boolean': case 'null': return String(value);
			case 'object':
				if (!value) return 'null';
				var partial = [];
				if (Object.prototype.toString.apply(value) === '[object Array]') {
					if (value.length === 0) return '[]';
					for (var i = 0, l = value.length; i < l; i++) partial[i] = str(i, value) || 'null';
					return ['[', partial.join(','), ']'].join('');
				}
				for (k in value) {
					if (Object.hasOwnProperty.call(value, k)) {
						var v = str(k, value);
						if (v) partial[partial.length] = [quote(k), ':', v].join('');
					}
				}
				return partial.length === 0 ? '{}' : ['{', partial.join(','), '}'].join('');
			}
		}
		return function(obj) { return str('', {'': obj}); };
	})();
};

