package hello

import (
    "appengine"
    "appengine/datastore"
    "bytes"
    "html/template"
    "net/http"
    "strings"
    "time"
)

type Greeting struct {
    Content []byte
    Date    time.Time
}

func (g *Greeting) String() string {
	return string(g.Content)
}

type ListItem struct {
    Key     *datastore.Key
    Device  string
    Result  string
    Full    []byte
}

func init() {
    http.HandleFunc("/", root)
    http.HandleFunc("/item/", item)
    http.HandleFunc("/guestbook", guestbook)
    http.HandleFunc("/sign", sign)
}

func root(w http.ResponseWriter, r *http.Request) {
    c := appengine.NewContext(r)
    q := datastore.NewQuery("Greeting").Order("-Date").Limit(50)
    greetings := make([]Greeting, 0, 50)
    keys, err := q.GetAll(c, &greetings)
    items := make([]ListItem, len(greetings));
    for i := range greetings {
        items[i].Key = keys[i];
        lines := strings.Split(string(greetings[i].Content), "\n")
        items[i].Device = lines[0]
        for j := range lines {
            if strings.HasPrefix(lines[j], "result:") {
                items[i].Result = lines[j][8:]
            }
        }
    }
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }
    if err := listTemplate.Execute(w, items); err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
    }
}

func item(w http.ResponseWriter, r *http.Request) {
    c := appengine.NewContext(r)
    spl := strings.Split(r.URL.Path, "/");
    key, err := datastore.DecodeKey(spl[2])
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }
    e := new(Greeting)
    if err := datastore.Get(c, key, e); err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }
    var o bytes.Buffer
    o.WriteString(`<!DOCTYPE html>
<html>
<pre>
`)
    o.WriteString(string(e.Content))
    o.WriteString("</pre>\n")
    w.Header().Set("Content-Type", "text/html")
    o.WriteTo(w)
}

func guestbook(w http.ResponseWriter, r *http.Request) {
    c := appengine.NewContext(r)
    q := datastore.NewQuery("Greeting").Order("-Date").Limit(10)
    greetings := make([]Greeting, 0, 10)
    _, err := q.GetAll(c, &greetings)
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }
    if err := guestbookTemplate.Execute(w, greetings); err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
    }
}

var guestbookTemplate = template.Must(template.New("book").Parse(guestbookTemplateHTML))

const guestbookTemplateHTML = `
<html>
  <body>
    {{range .}}
      <pre>{{.String}}</pre>
    {{end}}
    <form action="/sign" method="post">
      <div><textarea name="content" rows="3" cols="60"></textarea></div>
      <div><input type="submit" value="Sign Guestbook"></div>
    </form>
  </body>
</html>
`

var listTemplate = template.Must(template.New("book").Parse(listTemplateHTML))

const listTemplateHTML = `
<html>
  <body>
    {{range .}}
      <div><a href="/item/{{.Key.Encode}}">{{.Device}}</a> {{.Result}}</div>
    {{end}}
  </body>
</html>
`

func sign(w http.ResponseWriter, r *http.Request) {
    c := appengine.NewContext(r)
    g := Greeting{
        Content: []byte(r.FormValue("content")),
        Date:    time.Now(),
    }
    _, err := datastore.Put(c, datastore.NewIncompleteKey(c, "Greeting", nil), &g)
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }
    w.Write([]byte("ok"))
}
