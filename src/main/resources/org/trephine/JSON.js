/**
 * JSON.js - Adapted from Douglas Crocford's json2.js
 * by Jim R. Wilson <wilson.jim.r@gmail.com>
 * Modifications:
 *   - Stripped out unneeded functionality (space/indent, replacer, etc)
 *   - Changed native regex values to RegExp object instantiations (Opera)
 *   - Removed scope pollution (completely self-contained)
 *   - Encapsulated object creation into anonymous constructor for re-export
 *   - Removed most of the inline comments
 *   - Pulled RegExp objects and inner functions outside (reduce recompilation)
 */

if (!this.JSON) this.JSON = new (function(){

	// Format integers to have at least two digits.
	var f = function (n) { return n < 10 ? '0' + n : n; }
	
	// Helper function for encoding certain kinds of objects
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

	// Quote a string
	var qcallback = function (a) {
		var c = meta[a];
		return typeof c === 'string' ? c : '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4);
	};
	var quote = function (string) {
		escapable.lastIndex = 0;
		return ['"', (escapable.test(string) ? string.replace(escapable, qcallback) : string), '"'].join('');
	}
	
	// Produce a string from holder[key].
	var str = function (key, holder) {

		// Attempt toJSON() to find replacement value
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
	
	// The stringify function
	this.stringify = function(value) { return str('', {'': value}); };
	
	var cxcallback = function(a) { return '\\u' + ('0000' + a.charCodeAt(0).toString(16)).slice(-4); };
	
	// Regular expressions used in parse function to determine whether JSON is really malicious script
	var re = {
		test: new RegExp('^[\\],:{}\\s]*$'),
		at: new RegExp('\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4})','g'),
		bracket: new RegExp('"[^"\\\\\\n\\r]*"|true|false|null|-?\\d+(?:\\.\\d*)?(?:[eE][+\\-]?\\d+)?','g'),
		empty: new RegExp('(?:^|:|,)(?:\\s*\\[)+','g')
	};

	// The parse function - uses eval, but tests for safety first
	this.parse = function(text) {

		// Replace certain Unicode chars with escape sequences
		cx.lastIndex = 0;
		if (cx.test(text)) text = text.replace(cx, cxcallback);
		
		// Test whether text is suitable for evaluation, then evaluate
		if (re.test.test(text.replace(re.at,'@').replace(re.bracket,']').replace(re.empty,''))) return eval(['(',text,')'].join(''));

		// If the text is not JSON parseable, then a SyntaxError is thrown.
		throw new SyntaxError('JSON.parse');
	};
	
});
