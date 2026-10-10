package main

import (
	"errors"
	"fmt"
	"regexp"
	"slices"
	"sort"
	"strings"
	"sync"
)

const maxAmount int64 = 1000000000
const maxBalance int64 = 1000000000000

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

func split(amount int64, count int, kind string, values []int64) ([]int64, error) {
	shares := make([]int64, count)
	if kind == "EQUAL" {
		if len(values) != 0 {
			return nil, errors.New("equal split takes no values")
		}
		for index := range shares {
			shares[index] = amount / int64(count)
			if int64(index) < amount%int64(count) {
				shares[index]++
			}
		}
		return shares, nil
	}
	maximum := maxAmount
	if kind == "PERCENTAGE" {
		maximum = 10000
	}
	if len(values) != count {
		return nil, errors.New("invalid split values")
	}
	var total int64
	for _, value := range values {
		if value < 0 || value > maximum {
			return nil, errors.New("invalid split values")
		}
		total += value
	}
	if kind == "EXACT" {
		if total != amount {
			return nil, errors.New("exact shares must sum to amount")
		}
		return slices.Clone(values), nil
	}
	if kind != "PERCENTAGE" || total != 10000 {
		return nil, errors.New("unknown strategy or invalid percentage total")
	}
	order := make([]int, count)
	var allocated int64
	for index, value := range values {
		shares[index] = amount * value / 10000
		allocated += shares[index]
		order[index] = index
	}
	sort.SliceStable(order, func(left, right int) bool {
		return amount*values[order[left]]%10000 > amount*values[order[right]]%10000
	})
	for index := int64(0); index < amount-allocated; index++ {
		shares[order[index]]++
	}
	return shares, nil
}

type Group struct {
	members      map[string]bool
	balances     map[[2]string]int64
	history      []string
	transactions map[string]bool
}

type SplitWise struct {
	users  map[string]bool
	groups map[string]*Group
	gate   sync.Mutex
}

func NewSplitWise() *SplitWise {
	return &SplitWise{users: map[string]bool{}, groups: map[string]*Group{}}
}

func (s *SplitWise) AddUser(user string) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	if !idPattern.MatchString(user) || s.users[user] {
		return errors.New("invalid or duplicate user")
	}
	s.users[user] = true
	return nil
}

func uniqueMembers(members []string, allowed map[string]bool) (map[string]bool, error) {
	if len(members) == 0 || len(members) > 100 {
		return nil, errors.New("invalid participants")
	}
	result := map[string]bool{}
	for _, user := range members {
		if !allowed[user] || result[user] {
			return nil, errors.New("invalid participants")
		}
		result[user] = true
	}
	return result, nil
}

func (s *SplitWise) CreateGroup(id string, members []string) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	if !idPattern.MatchString(id) || s.groups[id] != nil {
		return errors.New("invalid group")
	}
	selected, err := uniqueMembers(members, s.users)
	if err != nil {
		return err
	}
	s.groups[id] = &Group{members: selected, balances: map[[2]string]int64{}, transactions: map[string]bool{}}
	return nil
}

func (s *SplitWise) AddMember(id, user string) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	group := s.groups[id]
	if group == nil || !s.users[user] || group.members[user] || len(group.members) >= 100 {
		return errors.New("invalid member")
	}
	group.members[user] = true
	return nil
}

func key(left, right string) [2]string {
	if left < right {
		return [2]string{left, right}
	}
	return [2]string{right, left}
}

func balance(group *Group, debtor, creditor string) (int64, error) {
	if group == nil || !group.members[debtor] || !group.members[creditor] {
		return 0, errors.New("unknown group or member")
	}
	amount := group.balances[key(debtor, creditor)]
	if debtor < creditor {
		return amount, nil
	}
	return -amount, nil
}

func (s *SplitWise) Balance(id, debtor, creditor string) (int64, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	return balance(s.groups[id], debtor, creditor)
}

func (s *SplitWise) RemoveMember(id, user string) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	group := s.groups[id]
	if group == nil || !group.members[user] {
		return errors.New("unknown member")
	}
	for other := range group.members {
		amount, _ := balance(group, user, other)
		if amount != 0 {
			return errors.New("settle balances before leaving")
		}
	}
	delete(group.members, user)
	return nil
}

func transaction(group *Group, id string, amount int64) error {
	if group == nil || !idPattern.MatchString(id) || amount <= 0 || amount > maxAmount || group.transactions[id] {
		return errors.New("unknown group, invalid amount or duplicate transaction")
	}
	return nil
}

func copyBalances(original map[[2]string]int64) map[[2]string]int64 {
	copied := map[[2]string]int64{}
	for pair, value := range original {
		copied[pair] = value
	}
	return copied
}

func change(balances map[[2]string]int64, debtor, creditor string, amount int64) error {
	if debtor == creditor {
		return nil
	}
	if debtor > creditor {
		amount = -amount
	}
	pair := key(debtor, creditor)
	updated := balances[pair] + amount
	if updated > maxBalance || updated < -maxBalance {
		return errors.New("balance limit exceeded")
	}
	balances[pair] = updated
	return nil
}

func (s *SplitWise) AddExpense(id, expense, payer string, amount int64, participants []string, kind string, values []int64) ([]int64, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	group := s.groups[id]
	if err := transaction(group, expense, amount); err != nil {
		return nil, err
	}
	if !group.members[payer] {
		return nil, errors.New("unknown payer")
	}
	if _, err := uniqueMembers(participants, group.members); err != nil {
		return nil, err
	}
	shares, err := split(amount, len(participants), kind, values)
	if err != nil {
		return nil, err
	}
	updated := copyBalances(group.balances)
	for index, user := range participants {
		if err := change(updated, user, payer, shares[index]); err != nil {
			return nil, err
		}
	}
	group.balances = updated
	group.history = append(group.history, fmt.Sprintf("EXPENSE %s %s %d %s %v %v", expense, payer, amount, kind, participants, shares))
	group.transactions[expense] = true
	return shares, nil
}

func (s *SplitWise) Settle(id, tx, debtor, creditor string, amount int64) error {
	s.gate.Lock()
	defer s.gate.Unlock()
	group := s.groups[id]
	if err := transaction(group, tx, amount); err != nil {
		return err
	}
	debt, err := balance(group, debtor, creditor)
	if err != nil || debtor == creditor || debt < amount {
		return errors.New("settlement exceeds debt or unknown member")
	}
	updated := copyBalances(group.balances)
	if err := change(updated, debtor, creditor, -amount); err != nil {
		return err
	}
	group.balances = updated
	group.history = append(group.history, fmt.Sprintf("SETTLE %s %s %s %d", tx, debtor, creditor, amount))
	group.transactions[tx] = true
	return nil
}

func (s *SplitWise) History(id string) ([]string, error) {
	s.gate.Lock()
	defer s.gate.Unlock()
	group := s.groups[id]
	if group == nil {
		return nil, errors.New("unknown group")
	}
	return slices.Clone(group.history), nil
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	service := NewSplitWise()
	members := []string{"A", "B", "C"}
	for _, user := range members {
		must(service.AddUser(user))
	}
	must(service.CreateGroup("trip", members))
	shares, err := service.AddExpense("trip", "dinner", "A", 100, members, "EQUAL", nil)
	must(err)
	text := []string{}
	for _, share := range shares {
		text = append(text, fmt.Sprint(share))
	}
	fmt.Println("Equal shares:", strings.Join(text, ","))
	_, err = service.AddExpense("trip", "taxi", "B", 60, []string{"A", "B"}, "EXACT", []int64{20, 40})
	must(err)
	_, err = service.AddExpense("trip", "tea", "C", 101, members, "PERCENTAGE", []int64{5000, 2500, 2500})
	must(err)
	debt, err := service.Balance("trip", "B", "A")
	must(err)
	fmt.Println("B owes A:", debt)
	debt, err = service.Balance("trip", "A", "C")
	must(err)
	fmt.Println("A owes C:", debt)
	must(service.Settle("trip", "payment", "B", "A", 13))
	debt, err = service.Balance("trip", "B", "A")
	must(err)
	fmt.Println("After settlement:", debt)
	history, err := service.History("trip")
	must(err)
	fmt.Println("History entries:", len(history))
}
