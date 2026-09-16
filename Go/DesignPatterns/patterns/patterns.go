// Package patterns contains independent, in-memory teaching demonstrations.
package patterns

import (
	"errors"
	"fmt"
	"strings"
	"sync"
)

type Notification interface {
	Send(string) error
}

type ChannelNotification struct {
	Channel string
}

func (n ChannelNotification) Send(message string) error {
	fmt.Println(n.Channel + ": " + message)
	return nil
}

var notificationCache = map[string]Notification{}

func notification(kind string) (Notification, error) {
	key := strings.ToUpper(kind)
	channels := map[string]string{"SMS": "SMS", "SLACK": "Slack", "WHATSAPP": "WhatsApp"}
	channel, ok := channels[key]
	if !ok {
		return nil, errors.New("Unknown notification type")
	}
	if notificationCache[key] == nil {
		notificationCache[key] = &ChannelNotification{channel}
	}
	return notificationCache[key], nil
}

func SimpleFactoryDesignPattern() {
	for _, kind := range []string{"SMS", "SLACK", "Whatsapp"} {
		n, _ := notification(kind)
		_ = n.Send("Hello")
	}
	a, _ := notification("SMS")
	b, _ := notification("sms")
	fmt.Printf("Same instance: %t\n", a == b)
	_, err := notification("EMAIL")
	fmt.Println(err)
}

type Creator interface {
	Create() Notification
}

type SMSFactory struct{}

func (SMSFactory) Create() Notification {
	return ChannelNotification{"SMS"}
}

type WhatsappFactory struct{}

func (WhatsappFactory) Create() Notification {
	return ChannelNotification{"WhatsApp"}
}

type PushFactory struct{}

func (PushFactory) Create() Notification {
	return ChannelNotification{"Push"}
}

func FactoryMethodDesignPattern() {
	for _, c := range []Creator{SMSFactory{}, WhatsappFactory{}, PushFactory{}} {
		_ = c.Create().Send("message")
	}
}

type Student struct {
	Name    string
	Age     int
	Address string
	Wallet  float64
}

type StudentBuilder struct {
	student Student
}

func NewStudentBuilder(name string) *StudentBuilder {
	return &StudentBuilder{Student{Name: name}}
}

func (b *StudentBuilder) SetAge(age int) *StudentBuilder {
	b.student.Age = age
	return b
}

func (b *StudentBuilder) SetAddress(address string) *StudentBuilder {
	b.student.Address = address
	return b
}

func (b *StudentBuilder) SetWallet(wallet float64) *StudentBuilder {
	b.student.Wallet = wallet
	return b
}

func (b *StudentBuilder) Build() Student {
	return b.student
}

func BuilderDesignPattern() {
	s := NewStudentBuilder("pradeep").SetAge(23).SetAddress("Delhi").SetWallet(100).Build()
	fmt.Printf("%s %d %s %.1f\n", s.Name, s.Age, s.Address, s.Wallet)
}

type configurationManager struct {
	path string
}

var configuration *configurationManager
var once sync.Once

func getConfiguration() *configurationManager {
	once.Do(func() {
		configuration = &configurationManager{path: "."}
	})
	return configuration
}

func Singleton() {
	fmt.Printf("Same instance: %t\n", getConfiguration() == getConfiguration())
}

type UIComponent struct {
	Family, Kind string
}

func (p UIComponent) Render() {
	fmt.Println(p.Family + " " + p.Kind + " rendered")
}

type UIFactory interface {
	Button() UIComponent
	Modal() UIComponent
	Screen() UIComponent
}

type FamilyFactory struct {
	Family string
}

func (f FamilyFactory) Button() UIComponent {
	return UIComponent{f.Family, "button"}
}

func (f FamilyFactory) Modal() UIComponent {
	return UIComponent{f.Family, "modal"}
}

func (f FamilyFactory) Screen() UIComponent {
	return UIComponent{f.Family, "screen"}
}

type UIRender struct{}

func (UIRender) Toggle(f UIFactory) {
	f.Button().Render()
	f.Modal().Render()
	f.Screen().Render()
}

func Abstract_FactoryDesign() {
	UIRender{}.Toggle(FamilyFactory{"linux"})
}

type RetryDecorator struct {
	Wrapped Notification
}

func (d RetryDecorator) Send(message string) error {
	var err error
	for i := 0; i < 3; i++ {
		err = d.Wrapped.Send(message)
		if err == nil {
			return nil
		}
	}
	return err
}

type FormattingDecorator struct {
	Wrapped Notification
}

func (d FormattingDecorator) Send(message string) error {
	return d.Wrapped.Send(strings.TrimSpace(message))
}

func DecoratorDesignPattern() {
	err := (FormattingDecorator{RetryDecorator{ChannelNotification{"SMS"}}}).Send(" Class starts at 1 PM ")
	if err != nil {
		panic(err)
	}
}

type PaymentProcessor interface {
	Pay(int)
}

type LegacyProcessor struct{}

func (LegacyProcessor) Pay(amount int) {
	fmt.Printf("Legacy payment: %d\n", amount)
}

type RazorPayProcessor struct{}

func (RazorPayProcessor) MakePayment(amount int) {
	fmt.Printf("Third-party payment: %d\n", amount)
}

type RazorPayAdapter struct {
	Processor RazorPayProcessor
}

func (a RazorPayAdapter) Pay(amount int) {
	a.Processor.MakePayment(amount)
}

type PaymentApplication struct {
	Processor PaymentProcessor
}

func (a PaymentApplication) Pay(amount int) error {
	if amount <= 0 {
		return errors.New("Amount must be positive")
	}
	a.Processor.Pay(amount)
	return nil
}

func AdapterDesignPattern() {
	app := PaymentApplication{LegacyProcessor{}}
	_ = app.Pay(100)
	app.Processor = RazorPayAdapter{}
	_ = app.Pay(200)
	fmt.Println(app.Pay(0))
}

type Database interface {
	Read(string) (string, bool)
	Write(string, string)
}

type MemoryDatabase struct {
	Data map[string]string
}

func (d *MemoryDatabase) Read(key string) (string, bool) {
	fmt.Println("DB read: " + key)
	value, ok := d.Data[key]
	return value, ok
}

func (d *MemoryDatabase) Write(key, value string) {
	d.Data[key] = value
}

type ProxyDatabase struct {
	Database Database
	Cache    map[string]string
}

func (d *ProxyDatabase) Read(key string) (string, bool) {
	if value, ok := d.Cache[key]; ok {
		fmt.Println("Cache hit: " + key)
		return value, true
	}
	value, ok := d.Database.Read(key)
	if ok {
		d.Cache[key] = value
	}
	return value, ok
}

func (d *ProxyDatabase) Write(key, value string) {
	d.Database.Write(key, value)
	delete(d.Cache, key)
}

func ProxyDesignPattern() {
	db := ProxyDatabase{&MemoryDatabase{map[string]string{}}, map[string]string{}}
	db.Write("lesson", "Java")
	value, _ := db.Read("lesson")
	fmt.Println(value)
	value, _ = db.Read("lesson")
	fmt.Println(value)
	db.Write("lesson", "Patterns")
	value, _ = db.Read("lesson")
	fmt.Println(value)
	if _, ok := db.Read("missing"); !ok {
		fmt.Println("Missing key")
	}
}

type Observer struct {
	Name string
}

func (o *Observer) Notify(value int) {
	fmt.Printf("%s: %d\n", o.Name, value)
}

type Stock struct {
	price     int
	observers []*Observer
}

func (s *Stock) Add(o *Observer) {
	for _, existing := range s.observers {
		if existing == o {
			return
		}
	}
	s.observers = append(s.observers, o)
}

func (s *Stock) Remove(o *Observer) {
	for i, existing := range s.observers {
		if existing == o {
			s.observers = append(s.observers[:i], s.observers[i+1:]...)
			return
		}
	}
}

func (s *Stock) SetPrice(value int) error {
	if value < 0 {
		return errors.New("Price cannot be negative")
	}
	if value == s.price {
		return nil
	}
	s.price = value
	for _, o := range append([]*Observer(nil), s.observers...) {
		o.Notify(value)
	}
	return nil
}

type ElevatorState struct {
	Floor int
}

type DisplayObserver interface {
	Notify(ElevatorState)
}

type FloorDisplay struct{}

func (*FloorDisplay) Notify(s ElevatorState) {
	fmt.Printf("Floor: %d\n", s.Floor)
}

type Elevator struct {
	observers []DisplayObserver
}

func (e *Elevator) Add(o DisplayObserver) {
	for _, existing := range e.observers {
		if existing == o {
			return
		}
	}
	e.observers = append(e.observers, o)
}

func (e *Elevator) Remove(o DisplayObserver) {
	for i, existing := range e.observers {
		if existing == o {
			e.observers = append(e.observers[:i], e.observers[i+1:]...)
			return
		}
	}
}

func (e *Elevator) MoveTo(floor int) {
	for _, o := range append([]DisplayObserver(nil), e.observers...) {
		o.Notify(ElevatorState{floor})
	}
}

func ObserverDesignPattern() {
	stock := Stock{}
	phone, tv := &Observer{"Phone"}, &Observer{"TV"}
	stock.Add(phone)
	stock.Add(phone)
	stock.Add(tv)
	_ = stock.SetPrice(10)
	_ = stock.SetPrice(10)
	stock.Remove(tv)
	_ = stock.SetPrice(20)
	elevator := Elevator{}
	elevator.Add(&FloorDisplay{})
	elevator.MoveTo(3)
}

type Server struct {
	Name        string
	Connections int
}

type Strategy interface {
	Select([]*Server) *Server
}

type RoundRobin struct {
	next int
}

func (r *RoundRobin) Select(servers []*Server) *Server {
	s := servers[r.next%len(servers)]
	r.next = (r.next + 1) % len(servers)
	return s
}

type LeastConnections struct{}

func (LeastConnections) Select(servers []*Server) *Server {
	best := servers[0]
	for _, s := range servers {
		if s.Connections < best.Connections {
			best = s
		}
	}
	return best
}

type LoadBalancer struct {
	Servers  []*Server
	Strategy Strategy
}

func (lb *LoadBalancer) Send(request string) (*Server, error) {
	if len(lb.Servers) == 0 {
		return nil, errors.New("No servers available")
	}
	s := lb.Strategy.Select(lb.Servers)
	s.Connections++
	fmt.Println(request + " -> " + s.Name)
	return s, nil
}

func (lb *LoadBalancer) Complete(s *Server) error {
	if s.Connections <= 0 {
		return errors.New("No active request")
	}
	s.Connections--
	return nil
}

func StrategyDesign() {
	lb := LoadBalancer{[]*Server{{"A", 2}, {"B", 0}}, &RoundRobin{}}
	first, _ := lb.Send("r1")
	_, _ = lb.Send("r2")
	_ = lb.Complete(first)
	lb.Strategy = LeastConnections{}
	_, _ = lb.Send("r3")
	empty := LoadBalancer{Strategy: &RoundRobin{}}
	_, err := empty.Send("r4")
	fmt.Println(err)
}

type PlayerState interface {
	Press(*Player)
}

type Paused struct{}

func (Paused) Press(p *Player) {
	fmt.Println("Playing")
	p.State = Playing{}
}

type Playing struct{}

func (Playing) Press(p *Player) {
	fmt.Println("Paused")
	p.State = Paused{}
}

type Player struct {
	State PlayerState
}

func (p *Player) Press() {
	p.State.Press(p)
}

func StateDesignPattern() {
	player := Player{Paused{}}
	player.Press()
	player.Press()
	player.Press()
}

type ATMState interface {
	Insert()
	Dispense()
	Cancel()
	Eject()
}

type ATM struct {
	state      ATMState
	noCard     *NoCard
	hasCard    *HasCard
	dispensing *Dispensing
}

type NoCard struct {
	atm *ATM
}

func (s *NoCard) Insert() {
	fmt.Println("Card inserted")
	s.atm.state = s.atm.hasCard
}

func (*NoCard) Dispense() {
	fmt.Println("Insert card first")
}

func (*NoCard) Cancel() {
	fmt.Println("No transaction")
}

func (*NoCard) Eject() {
	fmt.Println("No card")
}

type HasCard struct {
	atm *ATM
}

func (*HasCard) Insert() {
	fmt.Println("Card already inserted")
}

func (s *HasCard) Dispense() {
	fmt.Println("Dispensing started")
	s.atm.state = s.atm.dispensing
}

func (s *HasCard) Cancel() {
	fmt.Println("Cancelled")
	s.Eject()
}

func (s *HasCard) Eject() {
	fmt.Println("Card ejected")
	s.atm.state = s.atm.noCard
}

type Dispensing struct {
	atm *ATM
}

func (*Dispensing) Insert() {
	fmt.Println("Please wait")
}

func (*Dispensing) Dispense() {
	fmt.Println("Already dispensing")
}

func (*Dispensing) Cancel() {
	fmt.Println("Cannot cancel dispensing")
}

func (*Dispensing) Eject() {
	fmt.Println("Please wait")
}

func NewATM() *ATM {
	atm := &ATM{}
	atm.noCard = &NoCard{atm}
	atm.hasCard = &HasCard{atm}
	atm.dispensing = &Dispensing{atm}
	atm.state = atm.noCard
	return atm
}

func (a *ATM) Insert() {
	a.state.Insert()
}

func (a *ATM) Dispense() {
	a.state.Dispense()
}

func (a *ATM) Cancel() {
	a.state.Cancel()
}

func (a *ATM) Eject() {
	a.state.Eject()
}

func (a *ATM) Complete() {
	if a.state != a.dispensing {
		fmt.Println("Nothing to complete")
		return
	}
	fmt.Println("Cash dispensed: 100")
	a.state = a.hasCard
	a.Eject()
}

func ATMMachineStateDesign() {
	a := NewATM()
	a.Dispense()
	a.Insert()
	a.Insert()
	a.Dispense()
	a.Dispense()
	a.Cancel()
	a.Complete()
	a.Insert()
	a.Cancel()
	a.Complete()
}

type Node interface {
	Base() *NodeBase
	Size() int
}

type NodeBase struct {
	Name   string
	Parent *Folder
}

func (n *NodeBase) Base() *NodeBase {
	return n
}

func validName(name string) bool {
	return name != "" && !strings.Contains(name, "/")
}

func Rename(node Node, name string) error {
	if !validName(name) {
		return errors.New("Invalid name")
	}
	n := node.Base()
	if name == n.Name {
		return nil
	}
	if n.Parent != nil {
		if _, ok := n.Parent.children[name]; ok {
			return errors.New("Duplicate name")
		}
		delete(n.Parent.children, n.Name)
		n.Parent.children[name] = node
	}
	n.Name = name
	return nil
}

type File struct {
	NodeBase
	content string
}

func NewFile(name string) *File {
	if !validName(name) {
		panic("Invalid name")
	}
	return &File{NodeBase: NodeBase{Name: name}}
}

func (f *File) Size() int {
	return len(f.content)
}

func (f *File) Append(value string) {
	f.content += value
}

func (f *File) Modify(value string, offset int) error {
	if offset < 0 || offset > len(f.content) {
		return errors.New("Invalid offset")
	}
	end := offset + len(value)
	if end > len(f.content) {
		end = len(f.content)
	}
	f.content = f.content[:offset] + value + f.content[end:]
	return nil
}

func (f *File) Open() {
	fmt.Println(f.content)
}

type Folder struct {
	NodeBase
	children map[string]Node
	order    []Node
}

func NewFolder(name string) *Folder {
	if !validName(name) {
		panic("Invalid name")
	}
	return &Folder{NodeBase: NodeBase{Name: name}, children: map[string]Node{}}
}

func (f *Folder) Add(node Node) error {
	for ancestor := f; ancestor != nil; ancestor = ancestor.Parent {
		if ancestor.Base() == node.Base() {
			return errors.New("Cycle rejected")
		}
	}
	n := node.Base()
	if n.Parent != nil {
		return errors.New("Already owned")
	}
	if _, ok := f.children[n.Name]; ok {
		return errors.New("Duplicate name")
	}
	f.children[n.Name] = node
	f.order = append(f.order, node)
	n.Parent = f
	return nil
}

func (f *Folder) Size() int {
	total := 0
	for _, node := range f.children {
		total += node.Size()
	}
	return total
}

func (f *Folder) Open() {
	for _, node := range f.order {
		fmt.Println(node.Base().Name)
	}
}

func FileSystem_Node() {
	root, notes, file := NewFolder("root"), NewFolder("notes"), NewFile("draft.txt")
	_ = root.Add(notes)
	_ = notes.Add(file)
	file.Append("hello")
	_ = file.Modify("a", 1)
	_ = Rename(file, "lesson.txt")
	file.Open()
	fmt.Printf("%s: %d\n", root.Name, root.Size())
	notes.Open()
	fmt.Println(notes.Add(root))
	fmt.Println(notes.Add(NewFile("lesson.txt")))
	fmt.Println(file.Modify("!", 99))
}

type TV struct{}

func (TV) On() {
	fmt.Println("TV is ON")
}

func (TV) SetInput() {
	fmt.Println("TV input set to HDMI")
}

type SoundSystem struct{}

func (SoundSystem) On() {
	fmt.Println("Sound System is ON")
}

func (SoundSystem) SetVolume(v int) {
	fmt.Printf("Volume set to %d\n", v)
}

type StreamingDevice struct{}

func (StreamingDevice) On() {
	fmt.Println("Streaming Device is ON")
}

func (StreamingDevice) PlayMovie(movie string) {
	fmt.Println("Playing movie: " + movie)
}

type HomeTheaterFacade struct {
	tv        TV
	sound     SoundSystem
	streaming StreamingDevice
}

func (h HomeTheaterFacade) WatchMovie(movie string) {
	fmt.Print("Preparing Home Theater...\n\n")
	h.tv.On()
	h.tv.SetInput()
	h.sound.On()
	h.sound.SetVolume(20)
	h.streaming.On()
	h.streaming.PlayMovie(movie)
	fmt.Println("\nEnjoy your movie!")
}

func FacadePatternDemo() {
	HomeTheaterFacade{}.WatchMovie("Interstellar")
}
