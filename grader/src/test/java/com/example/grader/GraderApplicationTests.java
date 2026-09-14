package com.example.grader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Bật CẢ HAI vai: nếu chỉ để "test" thì không controller nào được nạp, và phép kiểm khởi động
// này sẽ không còn phát hiện được lỗi nối dây của controller nữa.
@SpringBootTest
@ActiveProfiles({"test", "gv", "nc"})
class GraderApplicationTests {

	@Test
	void contextLoads() {
	}

}
