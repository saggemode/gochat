package main

import (
	"encoding/json"
	"fmt"
	pb "gochat/gen/business"
)

func main() {
	resp := &pb.GetBusinessProfileResponse{
		Profile: &pb.BusinessProfile{
			UserId:         "123",
			BusinessName:   "Test",
			FollowersCount: 1,
		},
	}
	b, _ := json.Marshal(resp)
	fmt.Println("Marshal with count 1:", string(b))

	resp0 := &pb.GetBusinessProfileResponse{
		Profile: &pb.BusinessProfile{
			UserId:         "123",
			BusinessName:   "Test",
			FollowersCount: 0,
		},
	}
	b0, _ := json.Marshal(resp0)
	fmt.Println("Marshal with count 0:", string(b0))
}
